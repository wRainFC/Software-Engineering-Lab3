import {browse, checkout} from './customer.js';
import {admin, catalog, reports} from './management.js';
import {orderDetail, orderList, tasks} from './orders.js';
import {actions, api, btn, card, el, field, go, input, label, notify, para, refresh, state} from './ui.js';

const root = document.getElementById('app');
const menus = {
  CUSTOMER: [['browse', '选店点餐'], ['checkout', '购物车与地址'], ['orders', '我的订单']],
  MERCHANT: [['catalog', '店铺与菜品'], ['board', '订单履约看板'], ['reports', '统计与日志']],
  RIDER: [['tasks', '我的配送任务']],
  ADMIN: [['dispatch', '配送与退款'], ['admin', '账号与店铺'], ['reports', '统计与日志']]
};
let revision = 0;
function auth(register = false) {
  revision++;
  const account = input(), password = input('password');
  account.autocomplete = 'username';
  password.autocomplete = register ? 'new-password' : 'current-password';
  const submit = async () => {
    if (register)
      await api('/auth/register', 'POST', {account: account.value, password: password.value});
    state.actor =
        await api('/auth/login', 'POST', {account: account.value, password: password.value});
    go(menus[state.actor.role][0][0]);
  };
  const button = btn(register ? '注册并登录' : '登录', submit, 'pri');
  password.onkeydown = e => {
    if (e.key === 'Enter')
      button.click();
  };
  root.replaceChildren(
      el('div', 'auth',
         el('section', 'card',
            el('div', '', el('div', 'logo', el('span', 'logo-mark', '速'), '速达外卖'),
               el('h1', '', register ? '顾客注册' : '登录'), para('外卖点餐与配送系统')),
            el('div', 'stack', field('账号', account), field('密码', password), button,
               btn(register ? '返回登录' : '注册顾客账号', () => auth(!register), 'ghost'),
               el('small', 'muted', '教学演示 · 模拟支付与退款')))));
}
async function render() {
  if (!state.actor) {
    auth();
    return;
  }
  const rev = ++revision;
  const menu = menus[state.actor.role];
  let [view, id] = location.hash.slice(1).split('/');
  if (!menu.some(([v]) => v === view)) {
    view = menu[0][0];
    id = null;
  }
  const name = menu.find(([v]) => v === view)[1];
  const nav = el('nav', 'nav', menu.map(([v, title]) => {
    const a = el('a', v === view ? 'on' : '', title);
    a.href = '#' + v;
    return a;
  }));
  const aside =
      el('aside', 'side', el('div', 'logo', el('span', 'logo-mark', '速'), '速达外卖'),
         el('p', 'role-tag', label(state.actor.role) + '工作台'), nav,
         el('div', 'me', el('span', 'avatar', state.actor.account.slice(0, 1)),
            el('span', '', state.actor.account), btn('退出', async () => {
              await api('/auth/logout', 'POST');
              state.actor = null;
              auth();
            }, 'ghost')));
  const main = el('main', 'content');
  const layout =
      el('div', 'app', aside,
         el('div', 'main', el('header', 'top', el('h1', '', name), btn('刷新', refresh)), main));
  const loading = card(name, para('正在读取…'));
  main.append(loading);
  root.replaceChildren(layout);
  const body = el('div', 'stack');
  try {
    if (['orders', 'board', 'dispatch'].includes(view)) {
      if (id)
        await orderDetail(body, view, id);
      else
        await orderList(body, view);
    } else
      await ({browse, checkout, catalog, tasks, admin, reports})[view](body);
    if (rev === revision)
      main.replaceChildren(body);
  } catch (e) {
    if (rev === revision)
      main.replaceChildren(card('读取失败', para(e.message), actions(btn('重试', refresh))));
  }
}
window.addEventListener('hashchange', render);
window.addEventListener('refresh-view', render);
window.addEventListener('session-ended', () => auth());
try {
  state.actor = await api('/me');
} catch (e) {
  if (e.status !== 401)
    notify(e.message);
}
render();
