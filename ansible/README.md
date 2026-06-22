# Host configuration as code — Ansible

Brings the droplet to a known, reproducible state: base packages, automatic
security upgrades, Docker Engine + Compose, a host firewall (ufw), and certbot
auto-renewal for the Let's Encrypt certificate. Terraform creates the droplet;
Ansible configures what's *inside* it; Docker Compose runs the apps.

```
ansible/
├── ansible.cfg
├── requirements.yml          # community.general collection
├── inventory.ini.example     # copy to inventory.ini (gitignored)
├── group_vars/all.yml        # domain, paths, certbot email
├── playbook.yml              # base · docker · ufw · certbot
└── README.md
```

## ⚠️ This runs against a LIVE server — go slow

The playbook is idempotent (safe to re-run), but it configures your production
droplet. Always `--syntax-check`, then dry-run with `--check --diff`, read the
diff, and only then apply for real.

The **certbot** tasks are deliberately conservative: by default they set up the
renewal timer + a reload hook *around* your existing certificate — they do **not**
obtain or modify the live cert. Issuing a new cert is opt-in (`--tags certbot_issue`)
and only meant for a fresh rebuild.

## Prerequisites

- Ansible on your machine (`brew install ansible`, or `pipx install ansible`)
- The collections: `ansible-galaxy collection install -r requirements.yml`
- SSH access to the droplet from wherever you run this. Note: if the droplet's
  key ("github ssh key") only lives in your GitHub Actions secrets, add your
  laptop's key to the droplet (or run Ansible from a host that has the key).

## Configure

```bash
cd ansible
cp inventory.ini.example inventory.ini   # set ansible_host (terraform output -raw droplet_ip) + ansible_user
ansible-galaxy collection install -r requirements.yml
```

## Run (safe sequence)

```bash
ansible-playbook playbook.yml --syntax-check     # parse only
ansible all -m ping                              # confirm SSH works
ansible-playbook playbook.yml --check --diff     # dry run — shows what WOULD change
ansible-playbook playbook.yml                     # apply for real
```

On your already-configured droplet most tasks report **ok** (no change) — Docker
is already installed, etc. The likely real changes are: enabling ufw,
unattended-upgrades, and the certbot renewal hook/timer.

### ufw caution

The play allows 22/80/443 **before** enabling ufw, so an active SSH session is
never cut. Keep port 22 allowed — your GitHub Actions deploy connects over SSH
from changing IPs, so don't restrict SSH to a single address here.

### certbot / HTTPS

Renewal uses the **webroot** method, matching the `/.well-known/acme-challenge/`
location already in `nginx.conf`. For it to work end to end:

1. The nginx container must serve the webroot. Add to the `nginx` service in
   `docker-compose.yml`:
   ```yaml
   volumes:
     - /var/www/certbot:/var/www/certbot:ro
   ```
2. Test renewal without touching anything real:
   ```bash
   ssh root@<droplet> 'certbot renew --dry-run'
   ```
   If the dry run fails (e.g. your cert was originally issued with a different
   method), reconfigure it for webroot — then the timer + reload hook take over.

> I could not inspect your live host, so the certbot setup represents the
> recommended webroot configuration. Verify it with `--dry-run` before relying on
> it. Nothing here deletes or rewrites your current certificate.

## Backups (restic → DigitalOcean Space)

A nightly `systemd` timer runs restic, backing up `/etc/letsencrypt` and the
Docker volumes to a DigitalOcean Space, keeping 7 daily + 4 weekly snapshots. On
success it writes `backup_last_success_timestamp_seconds` to the node-exporter
textfile collector, which the **BackupStale** Prometheus alert watches (fires if
there's been no successful backup in over 26 hours).

### Set it up

1. In the DO console, create a **Space** and a pair of **Spaces access keys**, and
   set `backup_s3_region` / `backup_s3_bucket` in `group_vars/all.yml` to match.
2. Provide the secrets (kept out of git):
   ```bash
   cp secrets.yml.example secrets.yml      # fill in restic_password + the two keys
   ansible-vault encrypt secrets.yml        # optional but recommended
   ansible-playbook playbook.yml -e @secrets.yml [--ask-vault-pass]
   ```
   Without secrets the files install but the timer stays off (and BackupStale then
   correctly flags that backups aren't running).
3. **Keep `restic_password` somewhere safe** — without it the backups can't be restored.

### Restore

```bash
ssh root@<droplet> portfolio-restore.sh /restore   # latest snapshot -> /restore
ssh root@<droplet> 'set -a; . /etc/portfolio-backup/backup.env; set +a; restic snapshots'
```

> The success metric reaches Prometheus via the node-exporter textfile collector —
> that needs node-exporter started with
> `--collector.textfile.directory=/var/lib/node_exporter/textfile_collector` and the
> matching bind mount (both added in `docker-compose.yml`).

## Rebuild from scratch

On a fresh droplet (after `terraform apply`): run the playbook, add your DNS A
records at the registrar, then issue the cert once and bring up the stack:

```bash
ansible-playbook playbook.yml
ansible-playbook playbook.yml --tags certbot_issue    # set certbot_email in group_vars first
ssh root@<droplet> 'cd /opt/portfolio && docker compose up -d'
```
