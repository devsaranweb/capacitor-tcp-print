var capacitorTcpPrint = (function (exports, core) {
    'use strict';

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

    Object.defineProperty(exports, '__esModule', { value: true });

    return exports;

})({}, capacitorExports);
