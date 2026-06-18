terraform {
  required_version = ">= 1.5"

  required_providers {
    digitalocean = {
      source  = "digitalocean/digitalocean"
      version = "~> 2.0"
    }
  }

  # Optional: keep state in a DigitalOcean Space (S3-compatible) instead of on your
  # laptop. Create a Space + Spaces access keys, then uncomment and run
  # `terraform init -migrate-state`. Until then, state lives in the gitignored
  # terraform.tfstate (never commit it — it can contain secrets).
  #
  # backend "s3" {
  #   endpoints                   = { s3 = "https://fra1.digitaloceanspaces.com" }
  #   bucket                      = "andreasgabel-tfstate"
  #   key                         = "portfolio/terraform.tfstate"
  #   region                      = "us-east-1" # ignored by DO, but the s3 backend requires it
  #   skip_credentials_validation = true
  #   skip_metadata_api_check     = true
  #   skip_region_validation      = true
  #   skip_requesting_account_id  = true
  # }
}

provider "digitalocean" {
  token = var.do_token
}
