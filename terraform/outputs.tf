output "droplet_ip" {
  description = "Public IPv4 address of the droplet."
  value       = digitalocean_droplet.portfolio.ipv4_address
}

output "domain" {
  description = "Managed domain."
  value       = digitalocean_domain.portfolio.name
}

output "firewall_id" {
  description = "ID of the cloud firewall attached to the droplet."
  value       = digitalocean_firewall.portfolio.id
}
