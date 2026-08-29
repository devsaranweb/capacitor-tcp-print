import { WebPlugin } from '@capacitor/core';
/**
 * Web stub — browsers cannot open raw TCP sockets. Every method rejects with
 * `unavailable()` so callers can feature-detect.
 */
export class TcpPrintWeb extends WebPlugin {
    async print() {
        throw this.unavailable('TcpPrint is not available on web.');
    }
}
