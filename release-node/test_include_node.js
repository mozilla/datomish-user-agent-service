require('source-map-support').install();

process.on('uncaughtException', (err) => {
  console.log(err.stack);
  process.exit(101);
});

process.on('unhandledRejection', (reason, p) => {
  console.log(`Unhandled Rejection at: Promise ${JSON.stringify(p)}`);
  console.log(reason.stack);
  process.exit(102);
});

var d = require('../target/release-node/datomish_user_agent_service');
console.log("require succeeded!");

d.UserAgentService({
  port: 9090,
  db: "",
  version: "v1",
  contentServiceOrigin: "tofino://",
}).then(([start, stop]) => {
  console.log("starting");
  start();
// }).then(
//   console.log("stopping");
  // stop();
}).then((x) => {
  console.log("stopped", x);
}).catch(console.log);
