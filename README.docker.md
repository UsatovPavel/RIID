## Status
- Local (non-Docker) packaging isn’t ready yet; this image is a temporary wrapper to run the client in a container.
## Docker: build and run

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

## What this image does
- Builds the app with the Shadow plugin and runs the bundled JAR (`riid.app.Main` entrypoint).
- Pulls images from a registry using `RIID_REPO` and `RIID_REF` (or `RIID_TAG`/`RIID_DIGEST`) env vars.
- Stores cache under `/var/cache/riid` (mount as a named volume in compose).

## Configuration
- `RIID_REPO` (required): repository, e.g. `library/busybox`.
- `RIID_REF` (optional): tag or digest to pull; can also use `RIID_TAG` or `RIID_DIGEST`.
- `RIID_CACHE_DIR` (optional): override cache path; default `/var/cache/riid`.

## TODO
- Replace this stub README with README in PR 3, where App improves.