data "aws_availability_zones" "available" {
  state = "available"
}

data "aws_ssm_parameter" "al2023_arm64" {
  name = "/aws/service/ami-amazon-linux-latest/al2023-ami-kernel-default-arm64"
}

locals {
  zone = var.availability_zone != "" ? var.availability_zone : data.aws_availability_zones.available.names[0]
  base_hosts = {
    lg              = "c7g.2xlarge"
    edge            = "c7g.large"
    app-waiting     = "m7g.xlarge"
    app-reservation = "m7g.xlarge"
    redis           = "r7g.large"
    mysql           = "r7g.xlarge"
    mon             = "t4g.medium"
  }
  hosts = merge(local.base_hosts, var.enable_redis_ha ? {
    redis-replica-1 = "r7g.large"
    redis-replica-2 = "r7g.large"
  } : {})
}

resource "aws_vpc" "measurement" {
  cidr_block           = "10.84.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true
  tags = {
    Name = "movie-loadtest-vpc"
  }
}

resource "aws_internet_gateway" "measurement" {
  vpc_id = aws_vpc.measurement.id
  tags = {
    Name = "movie-loadtest-igw"
  }
}

resource "aws_subnet" "public" {
  vpc_id                  = aws_vpc.measurement.id
  cidr_block              = "10.84.1.0/24"
  availability_zone       = local.zone
  map_public_ip_on_launch = true
  tags = {
    Name = "movie-loadtest-public"
  }
}

resource "aws_route_table" "public" {
  vpc_id = aws_vpc.measurement.id
  tags = {
    Name = "movie-loadtest-public"
  }
}

resource "aws_route" "internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.measurement.id
}

resource "aws_route_table_association" "public" {
  subnet_id      = aws_subnet.public.id
  route_table_id = aws_route_table.public.id
}

resource "aws_security_group" "internal" {
  name_prefix = "movie-loadtest-internal-"
  description = "SSH from operator and traffic among measurement hosts"
  vpc_id      = aws_vpc.measurement.id
  tags = {
    Name = "movie-loadtest-internal"
  }
}

resource "aws_vpc_security_group_ingress_rule" "ssh" {
  security_group_id = aws_security_group.internal.id
  cidr_ipv4         = var.operator_cidr
  ip_protocol       = "tcp"
  from_port         = 22
  to_port           = 22
  description       = "SSH from the operator IP only"
}

resource "aws_vpc_security_group_ingress_rule" "internal" {
  security_group_id            = aws_security_group.internal.id
  referenced_security_group_id = aws_security_group.internal.id
  ip_protocol                  = "-1"
  description                  = "Private traffic between measurement hosts"
}

resource "aws_vpc_security_group_egress_rule" "internet" {
  security_group_id = aws_security_group.internal.id
  cidr_ipv4         = "0.0.0.0/0"
  ip_protocol       = "-1"
  description       = "Outbound downloads and AWS APIs"
}

resource "aws_security_group" "grafana" {
  name_prefix = "movie-loadtest-grafana-"
  description = "Grafana only from the operator IP"
  vpc_id      = aws_vpc.measurement.id
  tags = {
    Name = "movie-loadtest-grafana"
  }
}

resource "aws_vpc_security_group_ingress_rule" "grafana" {
  security_group_id = aws_security_group.grafana.id
  cidr_ipv4         = var.operator_cidr
  ip_protocol       = "tcp"
  from_port         = 3000
  to_port           = 3000
  description       = "Grafana from the operator IP only"
}

resource "aws_key_pair" "measurement" {
  key_name   = "movie-loadtest"
  public_key = file(pathexpand(var.ssh_public_key_path))
  tags = {
    Name = "movie-loadtest"
  }
}

resource "aws_ebs_volume" "mysql_data" {
  availability_zone = local.zone
  size              = 100
  type              = "gp3"
  iops              = 3000
  throughput        = 125
  encrypted         = true
  tags = {
    Name = "movie-loadtest-mysql-data"
  }
}

resource "aws_instance" "host" {
  for_each = local.hosts

  ami                         = data.aws_ssm_parameter.al2023_arm64.value
  instance_type               = each.value
  subnet_id                   = aws_subnet.public.id
  associate_public_ip_address = true
  vpc_security_group_ids      = each.key == "mon" ? [aws_security_group.internal.id, aws_security_group.grafana.id] : [aws_security_group.internal.id]
  key_name                    = aws_key_pair.measurement.key_name
  user_data = templatefile("${path.module}/user-data.sh.tftpl", {
    role            = each.key
    sysctl_base64   = base64encode(file("${path.module}/../tuning/sysctl.conf"))
    limits_base64   = base64encode(file("${path.module}/../tuning/limits.conf"))
    thp_base64      = base64encode(file("${path.module}/../tuning/disable-thp.sh"))
    mysql_volume_id = each.key == "mysql" ? aws_ebs_volume.mysql_data.id : ""
  })
  user_data_replace_on_change = true

  metadata_options {
    http_tokens = "required"
  }

  root_block_device {
    volume_type           = "gp3"
    volume_size           = 20
    iops                  = 3000
    throughput            = 125
    encrypted             = true
    delete_on_termination = true
  }

  dynamic "credit_specification" {
    for_each = each.key == "mon" ? [1] : []
    content {
      cpu_credits = "standard"
    }
  }

  volume_tags = {
    project = "movie-loadtest"
    Name    = "movie-loadtest-${each.key}-root"
  }

  tags = {
    Name = "movie-loadtest-${each.key}"
    role = each.key
  }
}

resource "aws_volume_attachment" "mysql_data" {
  device_name = "/dev/sdf"
  volume_id   = aws_ebs_volume.mysql_data.id
  instance_id = aws_instance.host["mysql"].id
}
