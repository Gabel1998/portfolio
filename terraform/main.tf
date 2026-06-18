# An SSH key that already exists in your DigitalOcean account (looked up by name,
# not managed here — Terraform won't try to create or delete it).
data "digitalocean_ssh_key" "default" {
  name = var.ssh_key_name
}

# The droplet that runs the whole Docker Compose platform.
#
# IMPORTANT: this server already exists. Import it before the first apply (see
# README) so Terraform adopts the live box instead of spinning up a second one.
# The droplet is configured by Docker Compose (and, later, Ansible) — not by
# Terraform — so we ignore image drift to keep Terraform from ever proposing a
# rebuild of the running server.
resource "digitalocean_droplet" "portfolio" {
  name     = var.droplet_name
  region   = var.region
  size     = var.droplet_size
  image    = var.droplet_image
  ssh_keys = [data.digitalocean_ssh_key.default.id]

  lifecycle {
    ignore_changes = [image]
  }
}

# --- DNS ---
# Importing the domain adopts the existing zone. Only the records declared below
# are managed by Terraform; any other records (MX, TXT, etc.) are left untouched.
resource "digitalocean_domain" "portfolio" {
  name = var.domain
}

resource "digitalocean_record" "root" {
  domain = digitalocean_domain.portfolio.name
  type   = "A"
  name   = "@"
  value  = digitalocean_droplet.portfolio.ipv4_address
  ttl    = 3600
}

resource "digitalocean_record" "www" {
  domain = digitalocean_domain.portfolio.name
  type   = "A"
  name   = "www"
  value  = digitalocean_droplet.portfolio.ipv4_address
  ttl    = 3600
}

# --- Cloud firewall ---
# Only SSH (restricted), HTTP and HTTPS inbound; all outbound allowed.
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
