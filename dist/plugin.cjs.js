'use strict';

Object.defineProperty(exports, '__esModule', { value: true });

var core = require('@capacitor/core');

const TcpPrint = core.registerPlugin('TcpPrint', {
    web: () => Promise.resolve().then(function () { return web; }).then((m) => new m.TcpPrintWeb()),
});

/**
 * Web stub — browsers cannot open raw TCP sockets. Every method rejects with
 * `unavailable()` so callers can feature-detect.
 *
 * `probe` rejects here even though it RESOLVES `{reachable:false}` natively
 * for an unreachable host: on web the question cannot be asked at all, and
 * answering "not reachable" would let a host app render a real verdict about
 * a printer it never contacted.
 */
class TcpPrintWeb extends core.WebPlugin {
    async print() {
        throw this.unavailable('TcpPrint is not available on web.');
    }
    async discover() {
        throw this.unavailable('TcpPrint is not available on web.');
    }
    async probe() {
        throw this.unavailable('TcpPrint is not available on web.');
    }
}

var web = /*#__PURE__*/Object.freeze({
    __proto__: null,
    TcpPrintWeb: TcpPrintWeb
});

exports.TcpPrint = TcpPrint;
