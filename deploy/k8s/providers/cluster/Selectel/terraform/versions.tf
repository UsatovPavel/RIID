terraform {
  required_version = ">= 1.5.0"

  required_providers {
    selectel = {
      source  = "selectel/selectel"
      version = "~> 8.3"
    }
  }
}

provider "selectel" {
  domain_name = var.account_id
  username    = var.iam_username
  password    = var.iam_password
  auth_url    = var.auth_url
  auth_region = var.region
}
