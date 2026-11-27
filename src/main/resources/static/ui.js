export const state = {
  actor: null
};
const notice = document.querySelector('#notice');
let noticeTimer;
export function notify(message) {
  clearTimeout(noticeTimer);
  notice.textContent = message;
  notice.hidden = false;
  noticeTimer = setTimeout(() => notice.hidden = true, 6500);
}
export async function api(path, method = 'GET', data) {
  const options = {method, headers: {'X-Requested-With': 'delivery'}};
  if (data !== undefined) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(data);
  }
  const res = await fetch('/api' + path, options);
  const raw = await res.text();
  let value;
  try {
    value = raw ? JSON.parse(raw) : null;
  } catch {
    throw new Error('响应中断，请刷新查询结果');
  }
  if (!res.ok) {
    if (res.status === 401 && state.actor) {
      state.actor = null;
      window.dispatchEvent(new Event('session-ended'));
    }
    throw Object.assign(new Error(value?.message || '请求失败'), {status: res.status, data: value});
  }
  return value;
}
export function el(tag, cls = '', ...children) {
  const node = document.createElement(tag);
  if (cls)
    node.className = cls;
  for (const child of children.flat(Infinity)) {
    if (child !== null && child !== undefined && child !== false)
      node.append(child);
  }
  return node;
}
export function btn(label, fn, kind = '') {
  const b = el('button', 'btn ' + kind, label);
  b.type = 'button';
  b.onclick = async () => {
    if (b.disabled)
      return;
    b.disabled = true;
    try {
      await fn();
    } catch (e) {
      notify(e.message);
    } finally {
      b.disabled = false;
    }
  };
  return b;
}
export function card(title, ...body) {
  return el('section', 'card', el('h2', '', title), ...body);
}
export function entry(title, ...body) {
  return el('div', 'entry', el('h4', '', title), ...body);
}
export function para(...text) {
  return el('p', '', ...text);
}
export function actions(...children) {
  return el('div', 'actions', ...children);
}
export function field(label, control) {
  return el('label', 'field', label, control);
}
export function input(type = 'text', value = '') {
  const n = el('input', 'input');
  n.type = type;
  n.value = value ?? '';
  return n;
}
export function select(pairs, value) {
  const n = el('select', 'input');
  for (const [v, label] of pairs) {
    const o = el('option', '', label);
    o.value = v;
    n.append(o);
  }
  if (value !== undefined)
    n.value = value;
  return n;
}
export function table(heads, rows) {
  return el(
      'table', '', el('thead', '', el('tr', '', heads.map(h => el('th', '', h)))),
      el('tbody', '', rows.map(row => el('tr', '', row.map(c => el('td', '', c ?? '—'))))));
}
export function money(cents) {
  return '¥' + (Number(cents) / 100).toFixed(2);
}
export function date(ms) {
  return ms ? new Intl
                  .DateTimeFormat(
                      'zh-CN', {dateStyle: 'short', timeStyle: 'medium', timeZone: 'Asia/Shanghai'})
                  .format(ms) :
              '—';
}
export const names = {
  CUSTOMER: '顾客',
  MERCHANT: '商家',
  ADMIN: '平台管理员',
  RIDER: '配送员',
  SYSTEM: '系统',
  GUEST: '访客',
  OPEN: '营业',
  PAUSED: '停业',
  CLOSED: '平台关闭',
  WAITING: '待接单',
  ACCEPTED: '已接单',
  COOKING: '制作中',
  READY: '待配送',
  DELIVERING: '配送中',
  EXCEPTION: '配送异常',
  DELIVERED: '已送达',
  COMPLETED: '已完成',
  CANCELLED: '已取消',
  PENDING: '待处理',
  PROCESSING: '退款中',
  FAILED: '失败',
  REFUNDED: '已退款',
  APPROVED: '已同意',
  DECLINED: '已驳回',
  EXPIRED: '已失效',
  ASSIGNED: '已分配',
  PICKED: '已取餐',
  REVOKED: '已撤销',
  DENIED: '拒绝',
  OK: '成功',
  ACCEPT: '接单',
  REJECT: '拒单',
  CANCEL: '直接取消',
  TIMEOUT: '超时取消',
  REQUEST_CANCEL: '申请取消',
  APPROVE: '同意取消',
  DECLINE: '驳回申请',
  COOK: '开始制作',
  CONFIRM: '确认收货',
  ASSIGN: '分配 / 改派',
  PICK: '确认取餐',
  DELIVER: '确认送达',
  RESTORE: '恢复待配送',
  ABORT: '终止履约并退款',
  RETRY_REFUND: '重试退款',
  PLACE: '提交订单',
  REFUND: '模拟退款'
};
export const label = s => names[s] ?? s ?? '—';
export const refundLabel = s => s === 'PENDING' ? '待退款' : s === 'FAILED' ? '退款失败' : label(s);
export const states = [
  'WAITING', 'ACCEPTED', 'COOKING', 'READY', 'DELIVERING', 'EXCEPTION', 'DELIVERED', 'COMPLETED',
  'CANCELLED'
];
export const tag = s => el('span', 'tag info', label(s));
export function qs(data) {
  return new URLSearchParams(Object.entries(data).filter(([, v]) => v !== '' && v != null))
      .toString();
}
export function modal(title, body, commit, button = '保存') {
  const d = el('dialog', '', el('h3', '', title), body);
  d.append(actions(btn('取消', () => d.close()), btn(button, async () => {
                     await commit();
                     d.close();
                   }, 'pri')));
  document.body.append(d);
  d.onclose = () => d.remove();
  d.showModal();
}
export function go(route) {
  if (location.hash === '#' + route)
    refresh();
  else
    location.hash = route;
}
export function refresh() {
  window.dispatchEvent(new Event('refresh-view'));
}
