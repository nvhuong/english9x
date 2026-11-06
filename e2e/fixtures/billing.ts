import { notImplemented } from './notImplemented';

/** Places a plan order for the user and confirms the bank transfer as an admin would. Needs the billing module. */
export async function paidOrder(_email: string, _plan: string): Promise<{ orderId: string }> {
  return notImplemented('billing.paidOrder', 'P5.2 (orders + manual confirmation)');
}
