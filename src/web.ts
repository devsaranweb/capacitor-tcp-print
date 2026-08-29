import { WebPlugin } from '@capacitor/core';

import type { TcpPrintPlugin } from './definitions';

/**
 * Web stub — browsers cannot open raw TCP sockets. Every method rejects with
 * `unavailable()` so callers can feature-detect.
 */
export class TcpPrintWeb extends WebPlugin implements TcpPrintPlugin {
  async print(): Promise<void> {
    throw this.unavailable('TcpPrint is not available on web.');
  }
}
