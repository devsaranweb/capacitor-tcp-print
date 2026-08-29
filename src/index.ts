import { registerPlugin } from '@capacitor/core';

import type { TcpPrintPlugin } from './definitions';

const TcpPrint = registerPlugin<TcpPrintPlugin>('TcpPrint', {
  web: () => import('./web').then((m) => new m.TcpPrintWeb()),
});

export * from './definitions';
export { TcpPrint };
