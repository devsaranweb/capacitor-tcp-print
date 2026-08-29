'use strict';

Object.defineProperty(exports, '__esModule', { value: true });

var core = require('@capacitor/core');

const TcpPrint = core.registerPlugin('TcpPrint', {
    web: () => Promise.resolve().then(function () { return web; }).then((m) => new m.TcpPrintWeb()),
});

/**
 * Web stub — browsers cannot open raw TCP sockets. Every method rejects with
 * `unavailable()` so callers can feature-detect.
 */
class TcpPrintWeb extends core.WebPlugin {
    async print() {
        throw this.unavailable('TcpPrint is not available on web.');
    }
}

var web = /*#__PURE__*/Object.freeze({
    __proto__: null,
    TcpPrintWeb: TcpPrintWeb
});

exports.TcpPrint = TcpPrint;
