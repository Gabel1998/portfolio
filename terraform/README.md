# Infrastructure as Code — DigitalOcean (Terraform)

Declares the platform's cloud infrastructure as code: the droplet that runs the
Docker Compose stack and a cloud firewall (only SSH/HTTP/HTTPS inbound). The OS
and services on the droplet are configured separately (Docker Compose today,
Ansible next) — Terraform only owns the cloud resources.

DNS for `andreasgabel.dk` is hosted **outside** DigitalOcean (at the registrar),
so it is not managed here.

```
terraform/
├── versions.tf   # required versions + DO provider + (optional) Spaces backend
├── variables.tf  # inputs (token, droplet name/region/size, ssh key, SSH CIDRs)
├── main.tf        # droplet + firewall
├── outputs.tf     # droplet IP, firewall id
├── import.sh      # one-shot helper: discover IDs + import the live infra + plan
└── terraform.tfvars.example
```

## ⚠️ The droplet already exists — import, don't re-create

The server is live. If you run `terraform apply` against empty state, Terraform
would create a **second** droplet. The safe path is to **import** the existing
droplet first (the `import.sh` helper does this), confirm `plan` is clean, and
only then is Terraform managing the real infrastructure.

## Prerequisites

- [Terraform](https://developer.hashicorp.com/terraform/install) ≥ 1.5
- A DigitalOcean API token (read/write): DO console → API → Tokens → Generate New Token
- An SSH key already uploaded to your DO account (its **name** goes in `ssh_key_name`)

## Quick start (recommended): the import helper

From the `terraform/` directory, with your token in the environment:

```bash
export DIGITALOCEAN_TOKEN=dop_v1_xxxxxxxx   # in your own shell — never commit it
./import.sh <droplet-name>                  # e.g. ./import.sh PrivatePortfolio
```

`import.sh` looks up the droplet's id/region/size and the firewall id via the DO
API, writes a starter `terraform.tfvars` that matches the live droplet (the token
is **not** written to disk — it stays in the environment), imports the droplet
(and firewall, if one exists) into state, and runs `terraform plan`.

Expect the **droplet to show no changes**. If there is no cloud firewall yet, the
plan shows it being **created** — that only adds protection (review before apply).

## Manual import (if you prefer)

```bash
cp terraform.tfvars.example terraform.tfvars   # fill in name/region/size to MATCH the droplet
terraform init
terraform import digitalocean_droplet.portfolio  <DROPLET_ID>
terraform import digitalocean_firewall.portfolio <FIREWALL_ID>   # skip if none exists
terraform plan
```

If `plan` proposes to destroy/recreate or rename the droplet, your
`droplet_name`/`region`/`droplet_size` don't match reality — fix the variables
and re-plan. **Do not apply a plan that destroys the droplet.**

## Apply

```bash
terraform apply
```

With everything imported, `apply` is a no-op for the droplet and (if you don't
have one yet) creates the firewall. From here the infra is reproducible from code.

## Rebuilding from scratch (disaster recovery)

On a clean account (empty state, no import), `terraform apply` provisions a fresh
droplet + firewall. You then bootstrap the host (Docker, `/opt/portfolio`, nginx,
certbot) — the Ansible step coming next — and bring the stack up with
`docker compose up -d`. DNS stays at your registrar; point its A records at the
new droplet IP (`terraform output droplet_ip`).

## Notes

- State (`*.tfstate`) and `terraform.tfvars` are gitignored — they can hold
  secrets. `.terraform.lock.hcl` **is** committed (it pins provider versions).
- `lifecycle { ignore_changes = [image, ssh_keys, user_data] }` on the droplet
  stops Terraform from proposing a rebuild over attributes DigitalOcean doesn't
  return on import.
