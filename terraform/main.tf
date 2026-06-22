# An SSH key that already exists in your DigitalOcean account (looked up by name,
# not managed here — Terraform won't try to create or delete it).
data "digitalocean_ssh_key" "default" {
  name = var.ssh_key_name
}

# The droplet that runs the whole Docker Compose platform.
#
# IMPORTANT: this server already exists. It is adopted via `terraform import`
# (see README / import.sh), never created from scratch against the live account.
# The OS and services are managed by Docker Compose (and, later, Ansible) — not
# Terraform. On import, DigitalOcean does not return ssh_keys/user_data, so they
# are ignored here; otherwise Terraform would propose to REBUILD the live droplet.
resource "digitalocean_droplet" "portfolio" {
  name     = var.droplet_name
  region   = var.region
  size     = var.droplet_size
  image    = var.droplet_image
  ssh_keys = [data.digitalocean_ssh_key.default.id]

  lifecycle {
    ignore_changes = [image, ssh_keys, user_data]
  }
}

# --- DNS ---
# The andreasgabel.dk zone is hosted OUTSIDE DigitalOcean (at the registrar), so
# it is intentionally not managed here. If you ever migrate the zone to DO, add
# digitalocean_domain + digitalocean_record resources and point your registrar's
# nameservers at ns1/ns2/ns3.digitalocean.com.

# --- Cloud firewall ---
# Only SSH (restricted), HTTP and HTTPS inbound; all outbound allowed. If the
# droplet has no cloud firewall yet, `terraform apply` creates and attaches this
# one (a security improvement — review the plan before applying).
resource "digitalocean_firewall" "portfolio" {
  name        = "${var.droplet_name}-fw"
  droplet_ids = [digitalocean_droplet.portfolio.id]

  inbound_rule {
    protocol         = "tcp"
    port_range       = "22"
    source_addresses = var.ssh_allowed_cidrs
  }

  inbound_rule {
    protocol         = "tcp"
    port_range       = "80"
    source_addresses = ["0.0.0.0/0", "::/0"]
  }

  inbound_rule {
    protocol         = "tcp"
    port_range       = "443"
    source_addresses = ["0.0.0.0/0", "::/0"]
  }

  outbound_rule {
    protocol              = "tcp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }

  outbound_rule {
    protocol              = "udp"
    port_range            = "1-65535"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }

  outbound_rule {
    protocol              = "icmp"
    destination_addresses = ["0.0.0.0/0", "::/0"]
  }
}
