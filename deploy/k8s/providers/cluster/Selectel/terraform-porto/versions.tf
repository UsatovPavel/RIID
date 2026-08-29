terraform {
  required_version = ">= 1.5.0"

  required_providers {
    # Selectel's own provider owns MKS only; plain cloud servers are ordinary
    # OpenStack objects, so the Porto stand talks to the same Keystone through
    # the upstream provider.
    openstack = {
      source  = "terraform-provider-openstack/openstack"
      version = "~> 3.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
  }
}

provider "openstack" {
  auth_url                = var.auth_url
  user_name               = var.iam_username
  password                = var.iam_password
  user_domain_name        = var.account_id
  tenant_id               = var.project_id
  region                  = var.region
  delayed_auth            = false
  allow_reauth            = true
  enable_logging          = false
  disable_no_cache_header = false
  max_retries             = 3
}
