import { registerPlugin } from '@capacitor/core';
const TcpPrint = registerPlugin('TcpPrint', {
    web: () => import('./web').then((m) => new m.TcpPrintWeb()),
});
export * from './definitions';
export { TcpPrint };
