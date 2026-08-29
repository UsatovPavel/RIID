# --- Credentials (exported by tf.sh from deploy/k8s/config/.env) ---

variable "account_id" {
  description = "Selectel account number, Keystone user_domain_name (SELECTEL_ACCOUNT_ID)."
  type        = string
}

variable "iam_username" {
  description = "Selectel service user name (SELECTEL_IAM_USER)."
  type        = string
}

variable "iam_password" {
  description = "Selectel service user password (SELECTEL_IAM_PASSWORD)."
  type        = string
  sensitive   = true
}

variable "project_id" {
  description = "VPC project id the stand is created in (SELECTEL_PROJECT_ID, 32 hex chars)."
  type        = string

  validation {
    condition     = can(regex("^[0-9a-f]{32}$", var.project_id))
    error_message = "project_id must be a 32-character hex id; run 'make project-id' in ../terraform to resolve it."
  }
}

variable "auth_url" {
  description = "Keystone endpoint of the Selectel cloud platform."
  type        = string
  default     = "https://cloud.api.selcloud.ru/identity/v3"
}

# --- Stand shape: same as the MKS stand (AGENT-98), different OS ---

variable "cluster_name" {
  description = "Prefix for every object of the stand."
  type        = string
  default     = "riid-porto"

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,30}$", var.cluster_name))
    error_message = "cluster_name must be a lowercase DNS-style label, 2 to 31 characters."
  }
}

variable "region" {
  description = "Selectel region."
  type        = string
  default     = "ru-3"
}

variable "availability_zone" {
  description = "Availability zone the servers are pinned to; must belong to var.region."
  type        = string
  # ru-3b, not ru-3a: ru-3a had no capacity for these nodes on the MKS stand and
  # every node group created there was rolled back by Selectel within a minute.
  default = "ru-3b"

  validation {
    condition     = startswith(var.availability_zone, var.region)
    error_message = "availability_zone must start with the region name, e.g. ru-3b for region ru-3."
  }
}

variable "nodes_count" {
  description = "Worker count: 6 for the preparation stand, 12 for the production run. The control plane is extra and is not counted, exactly as with the MKS node group."
  type        = number
  default     = 6

  validation {
    condition     = var.nodes_count >= 1 && var.nodes_count <= 100
    error_message = "nodes_count must be between 1 and 100."
  }
}

variable "flavor_name" {
  description = "Flavor of every node. SL1.4-8192 is the 4 vCPU / 8 GB shape the MKS stand uses."
  type        = string
  default     = "SL1.4-8192"
}

variable "control_plane_flavor_name" {
  description = "Flavor of the control plane. Same shape by default; it runs no workload."
  type        = string
  default     = "SL1.4-8192"
}

variable "image_name" {
  description = "Glance image. Porto ships debs for focal and jammy only, so the stand is pinned to jammy."
  type        = string
  default     = "Ubuntu 22.04 LTS 64-bit"
}

variable "volume_gb" {
  description = "Boot disk per node, GiB. The SL flavors carry no disk, so every server boots from a volume."
  type        = number
  default     = 140
}

variable "control_plane_volume_gb" {
  description = "Boot disk of the control plane, GiB."
  type        = number
  default     = 50
}

variable "volume_type_family" {
  description = "Network disk family; 'fast' is the SSD tier rated 25000/15000 IOPS and 500 MB/s."
  type        = string
  default     = "fast"
}

variable "subnet_cidr" {
  description = "Private network of the stand."
  type        = string
  default     = "10.10.0.0/24"

  validation {
    condition     = can(cidrhost(var.subnet_cidr, 0))
    error_message = "subnet_cidr must be a valid IPv4 CIDR."
  }
}

variable "pod_cidr" {
  description = "Pod network handed to kubeadm and flannel; must not overlap subnet_cidr."
  type        = string
  default     = "10.244.0.0/16"
}

variable "dns_nameservers" {
  description = "Resolvers handed out by DHCP on the private subnet."
  type        = list(string)
  default     = ["188.93.16.19", "188.93.17.19"]
}

variable "ssh_allowed_cidr" {
  description = "Who may reach ssh and the Kubernetes API of the control plane."
  type        = string
  default     = "0.0.0.0/0"
}

# --- Software versions ---

variable "kube_series" {
  description = "Minor series used for the pkgs.k8s.io apt repository, e.g. 1.34. Kept at the version the MKS stand runs so the two stands stay comparable."
  type        = string
  default     = "1.34"

  validation {
    condition     = can(regex("^1\\.[0-9]+$", var.kube_series))
    error_message = "kube_series must look like 1.34."
  }
}

variable "porto_version" {
  description = "Release tag of ten-nancy/porto to install, without the leading v. The jammy deb of that release is what lands on every node."
  type        = string
  default     = "5.3.41"

  validation {
    condition     = can(regex("^[0-9]+\\.[0-9]+\\.[0-9]+$", var.porto_version))
    error_message = "porto_version must look like 5.3.41."
  }
}

variable "porto_insecure_registries" {
  description = "Registries portod is allowed to talk to over plain HTTP, host[:port]. Porto has no per-command flag like podman's --tls-verify=false or ctr's --plain-http, so an HTTP registry has to be listed here."
  type        = list(string)
  default     = []
}

variable "labels" {
  description = "Extra kubernetes labels put on every worker at join time."
  type        = map(string)
  default     = {}
}

variable "kubeadm_token_ttl" {
  description = "Lifetime of the bootstrap token. Workers join within minutes; a long-lived token on a reachable API server is a cluster takeover waiting to happen."
  type        = string
  default     = "3h0m0s"
}
