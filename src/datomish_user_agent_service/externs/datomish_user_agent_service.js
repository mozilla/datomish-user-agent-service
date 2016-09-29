/*
 * Copyright 2012 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/**
 * @fileoverview Definitions for Express.js.
 * @see http://expressjs.com/api.html
 * @externs
 * @author Daniel Wirtz <dcode@dcode.io>
 */

/**
 * @type {function(new:Application, ...[*])}
 */
function express() {}

/**
 * @type {function(new:Application, ...[*])}
 */
express.application = function() {};

/**
 * @type {function(new:ExpressRequest, ...[*])}
 */
express.request = function() {};

/**
 * @type {function(new:ExpressResponse, ...[*])}
 */
express.response = function() {};

/**
 * @type {function(new:ExpressRoute, ...[*])}
 */
express.Route = function() {};

/**
 * @type {function(new:ExpressRouter, ...[*])}
 */
express.Router = function() {};

/**
 * @type {?function(...[*])}
 */
express.errorHandler = function() {};

/**
 * @name express.static
 * @function
 * @return {*}
 */
// Error: .\contrib\Express.js:63: ERROR - Parse error. missing name after . operator
// express.static = function() {};

/**
 * @type {string}
 */
express.errorHandler.title;

/**
 * @typedef {function(new:Application, ...)}
 */
var Application;

/**
 * @param {string} name
 * @param {*} value
 */
Application.prototype.set = function(name, value) {};

/**
 * @param {string} name
 */
Application.prototype.get = function(name) {};

/**
 * @param {string} name
 */
Application.prototype.enable = function(name) {};

/**
 * @param {string} name
 */
Application.prototype.disable = function(name) {};

/**
 * @param {string} name
 * @return {boolean}
 */
Application.prototype.enabled = function(name) {};

/**
 * @param {string} name
 * @return {boolean}
 */
Application.prototype.disabled = function(name) {};

/**
 * @param {string|Function} env
 * @param {Function=} callback
 */
Application.prototype.configure = function(env, callback) {};

/**
 * @param {string|Function} path
 * @param {Function=} func
 */
Application.prototype.use = function(path, func) {};

// TODO: Finish...

// var Router;

/**
 * @param {string|Function} path
 * @param {Function=} func
 */
express.Router.prototype.get = function(path, func) {};

/**
 * @param {string|Function} path
 * @param {Function=} func
 */
express.Router.prototype.put = function(path, func) {};

/**
 * @param {string|Function} path
 * @param {Function=} func
 */
express.Router.prototype.post = function(path, func) {};

/**
 * @param {string|Function} path
 * @param {Function=} func
 */
express.Router.prototype.delete = function(path, func) {};

var expressWs;
expressWs.extendExpress = function() {};
expressWs.createWebSocketServer = function(module, server, handler) {};

express.Router.prototype.ws = function(path, func) {};
express.Router.prototype.websocket = function(path, func) {};
express.Router.prototype.on = function(event, func) {};

express.request.prototype.accepts = function(mimeType) {};
express.request.prototype.is = function(mimeType) {};
express.request.prototype.get = function(key) {};
express.request.prototype.checkBody = function(value) {};
express.request.prototype.checkQuery = function(value) {};
express.request.prototype.optional = function(value) {};
express.request.prototype.notEmpty = function(value) {};
express.request.prototype.isInt = function(value) {};
express.request.prototype.validationErrors = function(value) {};

express.response.prototype.status = function(value) {};
express.response.prototype.set = function(value) {};
express.response.prototype.send = function(value) {};
express.response.prototype.json = function(value) {};
express.response.prototype.header = function(key, value) {};

express.static = function(path) {};

function morgan(style) {};

var bodyParser;
bodyParser.prototype.json = function(options) {};
bodyParser.prototype.text = function(options) {};
bodyParser.prototype.urlencoded = function(options) {};

var http = {};

/**
 * @typedef {function(http.IncomingMessage, http.ServerResponse)}
 */
http.requestListener;

/**
 * @param {http.requestListener=} listener
 * @return {http.Server}
 */
http.createServer = function(listener) {};

/**
 * @param {http.requestListener=} listener
 * @constructor
 * @extends events.EventEmitter
 */
http.Server = function(listener) {};

/**
 * @param {(number|string)} portOrPath
 * @param {(string|Function)=} hostnameOrCallback
 * @param {Function=} callback
 */
http.Server.prototype.listen = function(portOrPath, hostnameOrCallback, callback) {};

/**
 */
http.Server.prototype.close = function() {};
