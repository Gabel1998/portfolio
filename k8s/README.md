# Kubernetes (k3s) — a deliberately small learning artifact

This runs **two** of the platform's services (the portfolio frontend + backend) on
Kubernetes, as hand-written manifests **and** a Helm chart, with an Ingress
replacing Nginx for those services. It is **not** a migration of the live stack —
the production platform stays on Docker Compose (see the root `docker-compose.yml`).

The point of this directory is two-fold: show the Kubernetes fundamentals
(Deployments, Services, probes, resource requests/limits, securityContext, an
Ingress, and Helm templating), and — more importantly — show the **judgement** of
when Kubernetes is and isn't worth it (see the bottom of this file).

```
k8s/
├── manifests/        # hand-written: namespace, backend, frontend, ingress
├── chart/            # the same two services as a parametrised Helm chart
└── README.md
```

## Run it on k3s

Spin up a throwaway k3s node (a small droplet or a local VM) — k3s ships with the
Traefik ingress controller, so the Ingress works out of the box:

```bash
curl -sfL https://get.k3s.io | sh -        # single-node k3s
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
```

### Option A — raw manifests

```bash
kubectl apply -f k8s/manifests/
kubectl -n portfolio rollout status deploy/portfolio-backend
kubectl -n portfolio get pods,svc,ingress
```

### Option B — Helm

```bash
helm install portfolio k8s/chart --namespace portfolio --create-namespace
helm test portfolio   # (no tests defined; use kubectl to inspect)
kubectl -n portfolio get all
```

For local testing without real DNS, set the Ingress host to `<node-ip>.nip.io`
(e.g. `helm install ... --set ingress.host=203-0-113-4.nip.io`) and browse there.

## What it demonstrates

- **Deployments** with a backend **startupProbe** (gates a cold JVM start) plus
  split **liveness/readiness** probes via Spring Boot's probe groups
  (`/actuator/health/liveness` and `/readiness`) — a slow dependency marks the pod
  unready instead of restarting it. The frontend uses a simple `/` probe.
- **Resource requests/limits** on every container (scheduling + OOM protection).
- **securityContext**: the backend runs `runAsNonRoot` (numeric UID) with a
  read-only root filesystem (writable `/tmp` only) and all capabilities dropped;
  both pods set `seccompProfile: RuntimeDefault` and `automountServiceAccountToken:
  false`. The frontend keeps default caps + a writable rootfs because stock
  `nginx`'s master needs root to bind `:80` and forks workers via setuid —
  switching to `nginxinc/nginx-unprivileged` would let it match the backend.
- **Ingress** doing the path routing (`/api` → backend, `/` → frontend) that Nginx
  does in the Compose setup, served by k3s's bundled Traefik.
- **Helm**: the `chart/` renders the same two services from `values.yaml` via a
  `range` over a services map, with a shared labels helper.

## When is Kubernetes worth it — and when is Compose the right call?

Honest take, because this is the part that matters:

**Compose (what the live platform uses) is the right choice when:**
- It's a single host with a handful of services (this platform: one droplet, ~14
  containers).
- You want the lowest operational overhead and the fastest iteration.
- "Self-healing" is covered well enough by `restart: unless-stopped` + a health
  gate in the deploy pipeline.

**Kubernetes earns its complexity when you have:**
- More than one node, and you need the scheduler to place/reschedule and
  bin-pack workloads, or survive a node failure.
- Real horizontal scaling (HPA) and zero-downtime rolling updates, with fast
  declarative rollback (`kubectl rollout undo`, or a GitOps revert — automatic
  rollback needs Argo Rollouts / Flagger; core Deployments don't auto-revert).
- Many teams/services where namespaces, RBAC, and a declarative API pay off.
- An ecosystem need (operators, cert-manager, service mesh, GitOps).

**For this portfolio specifically:** on a single node, Kubernetes pod restarts are
the same self-healing class as Compose's `restart: unless-stopped`, and a
single-node k3s control plane is itself a SPOF — so k8s buys no availability here;
its rescheduling and node-failure benefits only appear with more than one node.
Even with k3s (deliberately lightweight — one binary, bundled Traefik) the real
cost isn't RAM, it's the operational surface you take on: cluster lifecycle,
upgrades, RBAC, and more manifests — all for **negative** benefit on one 2-GB box.
Compose is genuinely the better engineering choice here.

Note also that this artifact only moves the two **stateless** services. The
platform's stateful pieces (databases, volumes, the backup story) are the harder
part of any real migration — on k8s they'd need StatefulSets and a storage/backup
strategy (k3s's default local-path storage pins a pod to one node and doesn't
replicate), which at this scale favours Compose with host volumes even more.

This artifact exists so the Kubernetes skills are demonstrable, and to show that
*knowing when not to reach for k8s* is part of the skill set. If the platform ever
outgrew one box, these manifests are the starting point for the move.
