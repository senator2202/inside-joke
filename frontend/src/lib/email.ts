/** Same rule as the server (EmailLoginService.EMAIL): local@domain.tld, no spaces, max 254 chars. */
const EMAIL = /^[^\s@]{1,64}@[^\s@]{1,190}\.[^\s@]{2,63}$/;

export function isValidEmail(value: string): boolean {
  const email = value.trim();
  return email.length <= 254 && EMAIL.test(email);
}
