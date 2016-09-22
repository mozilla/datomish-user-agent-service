var d = require('../target/release-node/datomish_user_agent_service');
console.log("require succeeded!");

d.UserAgentService({
  port: 4000,
  db: "",
  version: "v1",
  contentServiceOrigin: "tofino://",
}).then((stop) => {
  console.log("stopping");
  // stop();
}).then((x) => {
  console.log("stopped", x);
}).catch(console.log);
