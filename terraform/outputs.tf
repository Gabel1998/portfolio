output "droplet_ip" {
  description = "Public IPv4 address of the droplet."
  value       = digitalocean_droplet.portfolio.ipv4_address
}

output "firewall_id" {
  description = "ID of the cloud firewall attached to the droplet."
  value       = digitalocean_firewall.portfolio.id
}
