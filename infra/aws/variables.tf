variable "operator_cidr" {
  description = "작업자 공인 IPv4 한 개의 /32 CIDR. SSH와 Grafana만 이 주소에서 접근한다."
  type        = string

  validation {
    condition     = can(cidrhost(var.operator_cidr, 0)) && endswith(var.operator_cidr, "/32")
    error_message = "operator_cidr must be one IPv4 /32 address."
  }
}

variable "ssh_public_key_path" {
  description = "EC2 SSH 키 페어로 등록할 로컬 공개 키 경로. 개인 키는 Terraform에 넣지 않는다."
  type        = string
  default     = "~/.ssh/id_ed25519.pub"
}

variable "availability_zone" {
  description = "비우면 서울 리전의 첫 사용 가능 AZ를 선택한다."
  type        = string
  default     = ""
}
