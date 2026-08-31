import { WebPlugin } from '@capacitor/core';
/**
 * Web stub — browsers cannot open raw TCP sockets. Every method rejects with
 * `unavailable()` so callers can feature-detect.
 *
 * `probe` rejects here even though it RESOLVES `{reachable:false}` natively
 * for an unreachable host: on web the question cannot be asked at all, and
 * answering "not reachable" would let a host app render a real verdict about
 * a printer it never contacted.
 */
export class TcpPrintWeb extends WebPlugin {
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
