import proxy from '../../../lib/auth-proxy.js';
export default (req, res) => proxy(req, res, 'sign-up/email');
