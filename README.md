# datomish-user-agent-service

```
Usage: bin/user-agent-service [options]

Options:
  -p, --port
    The port that the UserAgentService will run on. [number] [required]
  -d, --db
    The path to the directory containing the browser.db. [string] [required]
  -v, --version
    The version of API to use. [string] [choices: "v1"] [default: "v1"]
  -c, --content-service-origin
    The origin of the Content Service so that CORS can be enabled. [string] [default: "tofino://"]
```

[User Agent Service API spec](/docs/api.md)

## Development

For running the (minimal) tests, `lein doo node test` hot reloads the
test and package code.

For testing with cURL, run `lein figwheel dev` in one terminal, and
then `node target/release-node/datomish_user_agent_service.js` in
another terminal.  That'll hot reload the server code, taking care to
maintain the same HTTP server while swapping out the Express router.
(With a little work we'll be able to preserve the WebSocket
connections across swaps too.)

For testing against Tofino, modify the Tofino `app/packages.json` to
point at your local package directory and run `lein cljsbuild auto
release-node`.  That'll update the
`target/release-node/datomish-user-agent-service.js` in place, which
will get picked up by the bin script used by Tofino.  It works quite
well.
