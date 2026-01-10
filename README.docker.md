TODO: create normal readme
# Docker: build and run

Build the container image (from repository root):

```bash
docker build -t riid-demo .
```

Run with Docker:

```bash
docker run --rm -e RIID_REPO=library/busybox -e RIID_REF=latest -v riid_cache:/var/cache/riid -p 8080:8080 riid-demo
```
or 
```
docker run --rm riid-demo
```

```bash
docker compose up --build
```

Notes:
- The container builds an executable uber-JAR using the Shadow plugin and runs `riid.app.ImageLoadServiceFactory`.
- Configure repository and reference via `RIID_REPO` and `RIID_REF` environment variables.
- Cache is stored in `/var/cache/riid` (mounted to named volume in compose).
