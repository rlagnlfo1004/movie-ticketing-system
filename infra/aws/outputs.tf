output "public_ips" {
  value = { for name, host in aws_instance.host : name => host.public_ip }
}

output "private_ips" {
  value = { for name, host in aws_instance.host : name => host.private_ip }
}

output "availability_zone" {
  value = local.zone
}

output "mysql_data_volume_id" {
  value = aws_ebs_volume.mysql_data.id
}

output "hosts_env" {
  value = join("\n", [
    "LG_PUBLIC_IP=${aws_instance.host["lg"].public_ip}",
    "LG_PRIVATE_IP=${aws_instance.host["lg"].private_ip}",
    "EDGE_PUBLIC_IP=${aws_instance.host["edge"].public_ip}",
    "EDGE_PRIVATE_IP=${aws_instance.host["edge"].private_ip}",
    "APP_WAITING_PUBLIC_IP=${aws_instance.host["app-waiting"].public_ip}",
    "APP_WAITING_PRIVATE_IP=${aws_instance.host["app-waiting"].private_ip}",
    "APP_RESERVATION_PUBLIC_IP=${aws_instance.host["app-reservation"].public_ip}",
    "APP_RESERVATION_PRIVATE_IP=${aws_instance.host["app-reservation"].private_ip}",
    "REDIS_PUBLIC_IP=${aws_instance.host["redis"].public_ip}",
    "REDIS_PRIVATE_IP=${aws_instance.host["redis"].private_ip}",
    "REDIS_REPLICA_1_PUBLIC_IP=${try(aws_instance.host["redis-replica-1"].public_ip, "")}",
    "REDIS_REPLICA_1_PRIVATE_IP=${try(aws_instance.host["redis-replica-1"].private_ip, "")}",
    "REDIS_REPLICA_2_PUBLIC_IP=${try(aws_instance.host["redis-replica-2"].public_ip, "")}",
    "REDIS_REPLICA_2_PRIVATE_IP=${try(aws_instance.host["redis-replica-2"].private_ip, "")}",
    "MYSQL_PUBLIC_IP=${aws_instance.host["mysql"].public_ip}",
    "MYSQL_PRIVATE_IP=${aws_instance.host["mysql"].private_ip}",
    "MON_PUBLIC_IP=${aws_instance.host["mon"].public_ip}",
    "MON_PRIVATE_IP=${aws_instance.host["mon"].private_ip}",
    ""
  ])
}
