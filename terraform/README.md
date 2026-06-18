# Infrastructure as Code — DigitalOcean (Terraform)

This declares the live platform's cloud infrastructure as code: the droplet that
runs the Docker Compose stack, the `andreasgabel.dk` DNS records, and a cloud
firewall (only SSH/HTTP/HTTPS inbound). The OS and services on the droplet are
configured separately (Docker Compose today, Ansible next) — Terraform only owns
the cloud resources.

```
terraform/
├── versions.tf   # required versions + DO provider + (optional) Spaces backend
├── variables.tf  # inputs (token, region, size, ssh key, allowed SSH CIDRs)
├── main.tf        # droplet, domain, A records, firewall
├── outputs.tf     # droplet IP, domain, firewall id
└── terraform.tfvars.example
```

## ⚠️ The droplet already exists — import, don't re-create

The server is live. If you run `terraform apply` against empty state, Terraform
will create a **second** droplet instead of adopting the running one. The safe
path is to **import** the existing resources first, confirm `plan` is clean, and
only then is Terraform managing the real infrastructure.

## Prerequisites

- [Terraform](https://developer.hashicorp.com/terraform/install) ≥ 1.5
- A DigitalOcean API token (read/write): DO console → API → Generate New Token
- An SSH key already uploaded to your DO account (its **name** goes in `ssh_key_name`)
- Optional: [`doctl`](https://docs.digitalocean.com/reference/doctl/how-to/install/) to look up resource IDs from the CLI

## 1. Configure

```bash
cd terraform
cp terraform.tfvars.example terraform.tfvars
# edit terraform.tfvars: do_token, ssh_key_name, and set region/droplet_size
# to MATCH the existing droplet exactly (check the DO console).
terraform init
```

## 2. Import the existing infrastructure

Get the resource IDs (via `doctl` or the DO console URLs), then import each one.

```bash
# Droplet id:        doctl compute droplet list
terraform import digitalocean_droplet.portfolio <DROPLET_ID>

# Domain is keyed by its name:
terraform import digitalocean_domain.portfolio andreasgabel.dk

# DNS records:       doctl compute domain records list andreasgabel.dk
terraform import digitalocean_record.root andreasgabel.dk,<ROOT_RECORD_ID>
terraform import digitalocean_record.www  andreasgabel.dk,<WWW_RECORD_ID>

# Firewall id:       doctl compute firewall list
terraform import digitalocean_firewall.portfolio <FIREWALL_ID>
```

> If there is no cloud firewall yet, skip its import — `terraform apply` will
> create it and attach it to the droplet (this only adds protection).

## 3. Confirm the plan is clean

```bash
terraform plan
```

Expect **no changes** (or only the firewall being created, if you didn't have
one). If `plan` proposes to destroy/recreate the droplet, your `region` or
`droplet_size` doesn't match reality — fix the variables and re-plan. **Do not
apply a plan that destroys the droplet.**

## 4. Apply

```bash
terraform apply
```

From here the platform is reproducible from code: `terraform plan` shows drift,
and the droplet/DNS/firewall can be rebuilt from this repo.

## Rebuilding from scratch (disaster recovery)

On a clean DO account (empty state, no import), `terraform apply` provisions a
fresh droplet + DNS + firewall. You then bootstrap the host (install Docker,
lay down `/opt/portfolio`, nginx, certbot) — this is the Ansible step coming
next in the roadmap — and bring the stack up with `docker compose up -d`.

## Notes

- State (`*.tfstate`) and `terraform.tfvars` are gitignored — they hold secrets.
  `.terraform.lock.hcl` **is** committed (it pins provider versions).
- `lifecycle { ignore_changes = [image] }` on the droplet stops Terraform from
  ever proposing to rebuild the live box over a base-image change.
- Only the `@` and `www` records are managed here; other DNS records in the zone
  (e.g. mail) are left untouched.
