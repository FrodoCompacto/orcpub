# Deploy na VM (Docker full, host nginx existente)

Guia para subir a stack completa (`datomic` + `orcpub` + `web`) numa VM que já
tem nginx na porta 80/443 — como a **salve-vm**.

O container `web` escuta em **loopback** nas portas **8880** (HTTP) e **8843**
(HTTPS), sem conflitar com o nginx do host. Depois você aponta um `server_name`
no nginx do host para `127.0.0.1:8843` (Cloudflare fica para um passo posterior).

## Pré-requisitos na VM

| Item | Notas |
|------|-------|
| RAM | ≥ 4 GB livres no primeiro build (sua VM: ~4 GB available — ok) |
| Disco | Build + `./data` — ~2–5 GB |
| Docker | Engine + plugin `docker compose` v2 |
| Portas livres | 8880, 8843 no loopback (4334 fica só na rede Docker) |

Instalar Docker (Ubuntu), se ainda não tiver:

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker ubuntu
# logout/login ou: newgrp docker
docker compose version
```

## 1. Clonar o fork

```bash
sudo mkdir -p /opt/orcpub-app
sudo chown ubuntu:ubuntu /opt/orcpub-app
cd /opt/orcpub-app
git clone https://github.com/<seu-usuario>/orcpub.git .
```

## 2. Configurar ambiente VM

```bash
./scripts/vm-deploy.sh setup
```

Isso roda `./run --auto` (senhas aleatórias, certs snakeoil, pastas `data/` e `logs/`)
e aplica as portas loopback no `.env`. **Não** copie `.env.vm.example` antes — ele
serve só de referência.

Confira no `.env`:

```env
WEB_HTTP_PUBLISH=127.0.0.1:8880:80
WEB_HTTPS_PUBLISH=127.0.0.1:8843:443
COMPOSE_FILE=docker-compose.yaml:docker-compose.vm.yaml
DEV_MODE=
```

## 3. Build e subir

```bash
./scripts/vm-deploy.sh all
```

Ou passo a passo:

```bash
./scripts/vm-deploy.sh build
./scripts/vm-deploy.sh up
./scripts/vm-deploy.sh status
```

O primeiro build pode levar **10–20 minutos**. O app demora ~2 min para ficar
`healthy` após o datomic subir.

## 4. Criar usuário admin

Opção A — variáveis no `.env`:

```env
INIT_ADMIN_USER=admin
INIT_ADMIN_EMAIL=voce@example.com
INIT_ADMIN_PASSWORD=<senha-forte>
```

```bash
./docker-user.sh init
```

Opção B — direto:

```bash
./docker-user.sh create admin voce@example.com '<senha-forte>'
```

## 5. Testar (sem Cloudflare ainda)

**Na VM:**

```bash
curl -sk https://127.0.0.1:8843/health
curl -sk -o /dev/null -w '%{http_code}\n' https://127.0.0.1:8843/
docker compose ps
sudo ss -lntup | grep -E '8880|8843'
```

Esperado: `8880` e `8843` em `127.0.0.1`, **não** em `0.0.0.0`.

**Do seu PC (túnel SSH):**

```powershell
ssh -i "$env:USERPROFILE\.ssh\oracle-salve.key" -L 8843:127.0.0.1:8843 ubuntu@150.136.96.68
```

Abra `https://localhost:8843` e aceite o certificado autoassinado (snakeoil).

## 6. Integrar com nginx do host (quando tiver subdomínio)

Exemplo futuro (`/etc/nginx/sites-available/orcpub`):

```nginx
server {
    listen 443 ssl;
    server_name dmv.frodo.cloud;

    # cert Let's Encrypt ou origin cert Cloudflare
    ssl_certificate     ...;
    ssl_certificate_key ...;

    location / {
        proxy_pass https://127.0.0.1:8843;
        proxy_ssl_verify off;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

Alternativa: proxy HTTP para `http://127.0.0.1:8880` (redirect interno para HTTPS).

## Comandos úteis

```bash
docker compose ps
docker compose logs orcpub --tail 80
docker compose logs datomic --tail 50
docker compose restart orcpub
./docker-user.sh list
```

## Atualizar após `git pull`

```bash
cd /opt/orcpub-app
git pull
docker compose build
docker compose up -d
```

## Backup

Com a stack **parada** ou via ferramenta de backup:

```bash
./docker-migrate.sh backup
# ou copiar ./data enquanto datomic está down:
# docker compose stop datomic orcpub web
# tar czf orcpub-data-$(date +%F).tar.gz data/
```

## Troubleshooting

| Sintoma | Ação |
|---------|------|
| `port is already allocated` em 80/443 | Falta `COMPOSE_FILE=...vm.yaml` ou vars `WEB_*` no `.env` |
| orcpub `unhealthy` | `docker compose logs orcpub` — esperar 2–3 min; checar `DATOMIC_PASSWORD` |
| Build OOM | `free -h`; considerar swap temporário ou build local + push de imagem |
| Login falha | `SIGNATURE` mudou? Recriar sessão; `./docker-user.sh check admin` |

## Autenticação (Cloudflare Access SSO)

O app **não usa mais tela de login/registro**. Sessão é criada automaticamente via
`GET /auth/session`:

1. Cloudflare Access autentica o visitante (Google, OTP, etc.).
2. O proxy envia o header `Cf-Access-Jwt-Assertion` para o app.
3. O backend valida o JWT (RS256, `aud`, `exp`, `iss`) contra os certs da CF.
4. O email do claim provisiona (find-or-create) o usuário no Datomic.
5. O browser recebe o JWT interno do app (`SIGNATURE`) para chamadas à API.

**Nunca** confie só em `CF-Access-Authenticated-User-Email` — identidade vem do JWT
validado.

### Variáveis no `.env` de produção

```env
AUTH_MODE=cloudflare
CF_ACCESS_TEAM_DOMAIN=arcaneinfra.cloudflareaccess.com
CF_ACCESS_AUD=<Application Audience do app no Zero Trust>
```

Copie o **Application Audience (AUD)** em:
Zero Trust → Access → Applications → `dmv.frodo.cloud` → Settings.

**Não** defina `AUTH_MODE=dev` nem `DEV_AUTH_EMAIL` em produção.

### Dev local

```env
AUTH_MODE=dev
DEV_AUTH_EMAIL=test@test.com
DEV_AUTH_USERNAME=test
```

```bash
./menu start server
# ou docker equivalente — abre direto no builder, sem Cloudflare
```

### Smoke test pós-deploy

1. Abrir `https://dmv.frodo.cloud` (sessão CF limpa).
2. Passar pelo challenge Cloudflare Access.
3. App abre no character builder **sem** tela de login.
4. Header mostra o email do usuário.

### Rotação / troubleshooting auth

| Sintoma | Ação |
|---------|------|
| 401 `cf-access-required` | Request não passou pelo Cloudflare Access (proxy direto?) |
| 401 `invalid-cf-access` | AUD errado, JWT expirado, ou team domain incorreto |
| 403 em POST `/login` | Esperado — login por senha desabilitado |
| Loop de loading | Checar logs `docker compose logs orcpub`; validar `SIGNATURE` |

## Melhorias planejadas (não urgentes)

Itens de endurecimento **deliberadamente adiados** após o go-live em
`dmv.frodo.cloud`. O ambiente já passou nos checks principais (portas loopback,
Cloudflare sem bypass, logs sem query string, secrets rotacionados).

### HSTS de 1 dia → 1 ano

**Hoje:** em `/etc/nginx/sites-available/dmv`:

```nginx
add_header Strict-Transport-Security "max-age=86400" always;
```

(mesmo valor do Salve — 24 horas)

**Plano:** alterar para `max-age=31536000` depois de algumas semanas estáveis.

**Por quê esperar:** o HSTS fica cacheado no navegador. Se certificado,
certbot, Cloudflare ou o proxy quebrarem, visitantes recorrentes ficam presos em
HTTPS com erro até o `max-age` expirar. Com 1 dia você corrige rápido; com 1
ano a janela de recuperação é longa. Só vale subir depois de confiar no ciclo
completo: Let's Encrypt renovando, Cloudflare em **Full (strict)**, nginx host
→ `127.0.0.1:8843` estável.

**Como aplicar (futuro):**

```bash
sudo sed -i 's/max-age=86400/max-age=31536000/' /etc/nginx/sites-available/dmv
sudo nginx -t && sudo systemctl reload nginx
```

Opcional depois: `includeSubDomains` — só se todos os subdomínios do cert
suportarem HTTPS-only.

### Migrar `.env` → `/etc/orcpub/app.env`

**Hoje:** secrets em `/opt/orcpub-app/orcpub/.env`, `chmod 600`, fora do git.
Funciona; `./run` e `docker compose` esperam o arquivo na raiz do clone.

**Plano:** copiar para `/etc/orcpub/app.env` (`root:root`, `600`) e apontar o
compose com `env_file:` — alinhado ao Salve (`/etc/salve/backend.env`).

**Por quê esperar:** não é falha de segurança imediata. Permissões já estão
corretas e senhas foram rotacionadas. A migração é **organização e
defesa-em-profundidade**: secrets fora do diretório do repositório, política
de backup mais clara, menos risco de vazar `.env` em tarball ou script que
varre o clone.

**Por quê fazer depois:** quem está no grupo `docker` tem poder equivalente a
root; separar secrets do clone reduz superfície acidental. Exige janela de
manutenção (restart da stack) e atualizar este runbook + VM_NOTES.

**Esboço (futuro):**

```bash
sudo mkdir -p /etc/orcpub
sudo cp /opt/orcpub-app/orcpub/.env /etc/orcpub/app.env
sudo chmod 600 /etc/orcpub/app.env
sudo chown root:root /etc/orcpub/app.env
# Ajustar docker-compose para env_file: /etc/orcpub/app.env
# docker compose up -d --force-recreate
```

Detalhes também em [README.md](../README.md#planned-production-hardening-vm).
