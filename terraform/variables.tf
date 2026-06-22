variable "do_token" {
  description = "DigitalOcean API token (read/write). Usually left unset — the provider reads the DIGITALOCEAN_TOKEN environment variable. Set TF_VAR_do_token only if you prefer to pass it explicitly."
  type        = string
  sensitive   = true
  default     = null
}

variable "droplet_name" {
  description = "Droplet name. MUST match the existing droplet's name when importing."
  type        = string
  default     = "portfolio"
}

variable "region" {
  description = "DigitalOcean region slug (e.g. fra1, ams3). MUST match the existing droplet when importing."
  type        = string
  default     = "fra1"
}

variable "droplet_size" {
  description = "Droplet size slug. MUST match the existing droplet when importing (otherwise a plan would propose a destructive resize/recreate)."
  type        = string
  default     = "s-2vcpu-4gb"
}

variable "droplet_image" {
  description = "Base image slug. Only used when creating a fresh droplet; ignored for the live box via lifecycle.ignore_changes."
  type        = string
  default     = "ubuntu-24-04-x64"
}

variable "ssh_key_name" {
  description = "Name of an SSH key already uploaded to your DigitalOcean account (looked up, not created)."
  type        = string
}

variable "ssh_allowed_cidrs" {
  description = "CIDRs allowed to reach SSH (port 22). Lock this down to your own IP(s) instead of the whole internet."
  type        = list(string)
  default     = ["0.0.0.0/0", "::/0"]
}
