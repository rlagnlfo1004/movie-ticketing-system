terraform {
  required_version = ">= 1.6.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region              = "ap-northeast-2"
  profile             = "movie-loadtest"
  allowed_account_ids = ["976071095824"]

  default_tags {
    tags = {
      project = "movie-loadtest"
    }
  }
}
