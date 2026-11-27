import {filterBox, query} from './orders.js';
import {actions, api, btn, card, date, el, entry, field, input, label, modal, money, para, qs, refresh, select, state, states, table, tag} from './ui.js';

function shopEdit(s = {}) {
  const n = input('text', s.name), p = input('tel', s.phone), a = input('text', s.areas),
        f = input('number', s.fee ?? 0);
  const status = select([['OPEN', '营业'], ['PAUSED', '停业']], s.status);
  modal(
      s.id ? '编辑店铺' : '新增店铺',
      el('div', 'stack', field('店名', n), field('联系电话', p), field('服务区域（以逗号分隔）', a),
         field('固定配送费（分）', f), field('状态', status)),
      async () => {
        await api('/shops' + (s.id ? '/' + s.id : ''), 'POST', {
          name: n.value,
          phone: p.value,
          areas: a.value,
          fee: Number(f.value),
          status: status.value,
          version: s.version ?? 0
        });
        refresh();
      });
}
function dishEdit(shop, d = {}) {
  const n = input('text', d.name), cat = input('text', d.category), p = input('number', d.price),
        qty = input('number', d.stock ?? 0);
  const shelf = select([['1', '上架'], ['0', '下架']], d.shelf === false ? '0' : '1');
  modal(
      d.id ? '编辑菜品' : '新增菜品',
      el('div', 'stack', field('菜名', n), field('分类', cat), field('单价（分）', p),
         field('可售库存', qty), field('销售状态', shelf)),
      async () => {
        await api('/shops/' + shop + '/dishes' + (d.id ? '/' + d.id : ''), 'POST', {
          name: n.value,
          category: cat.value,
          price: Number(p.value),
          stock: Number(qty.value),
          shelf: shelf.value === '1',
          version: d.version ?? 0
        });
        refresh();
      });
}
export async function catalog(main) {
  const [shops, dishes] = await Promise.all([api('/shops'), api('/dishes')]);
  main.append(actions(btn('新增店铺', () => shopEdit(), 'pri')));
  for (const s of shops) {
    const items = dishes.filter(d => d.shop_id === s.id);
    main.append(card(
        s.name,
        para(tag(s.status), ' · ' + s.areas + ' · ' + s.phone + ' · 配送费 ' + money(s.fee)),
        s.reason ? para(s.reason) : null,
        actions(
            s.status !== 'CLOSED' ? btn('编辑店铺', () => shopEdit(s)) : null,
            btn('新增菜品', () => dishEdit(s.id), 'tonal')),
        table(
            ['菜品', '分类', '单价', '库存', '状态', '操作'],
            items.map(
                d =>
                    [d.name, d.category, money(d.price), d.stock, d.shelf ? '上架' : '下架',
                     btn('编辑', () => dishEdit(s.id, d))]))));
  }
}
function provision() {
  const a = input(), p = input('password'), r = select([['MERCHANT', '商家'], ['RIDER', '配送员']]);
  modal(
      '开通账号', el('div', 'stack', field('账号', a), field('初始密码', p), field('角色', r)),
      async () => {
        await api('/admin/users', 'POST', {account: a.value, password: p.value, role: r.value});
        refresh();
      });
}
export async function admin(main) {
  const [users, shops] = await Promise.all([api('/admin/users'), api('/shops')]);
  main.append(
      card(
          '账号管理', btn('开通账号', provision, 'pri'),
          table(
              ['账号', '角色', '状态', '操作'],
              users.map(
                  u =>
                      [u.account, label(u.role), u.enabled ? '启用' : '停用',
                       u.id === state.actor.id ? '当前账号' :
                                                 btn(u.enabled ? '停用' : '启用',
                                                     async () => {
                                                       const reason = u.enabled ?
                                                           prompt('请输入停用原因') :
                                                           '';
                                                       if (reason === null)
                                                         return;
                                                       await api(
                                                           '/admin/users/' + u.id + '/access',
                                                           'POST', {enabled: !u.enabled, reason});
                                                       refresh();
                                                     })]))),
      card(
          '店铺监管',
          table(
              ['店铺', '状态', '原因', '操作'],
              shops.map(
                  s =>
                      [s.name, label(s.status), s.reason,
                       s.status === 'CLOSED' ? '—' : btn('强制关店', async () => {
                         const reason = prompt('请输入关店原因');
                         if (reason === null)
                           return;
                         await api('/shops/' + s.id + '/close', 'POST', {reason});
                         refresh();
                       }, 'danger')]))));
}
let logFilter = {};
export async function reports(main) {
  const [shops, groups, logs] = await Promise.all(
      [api('/shops'), api('/statistics?' + query('reports')), api('/logs?' + qs(logFilter))]);
  main.append(card(
      '订单与模拟资金', filterBox('reports', shops, false),
      el('div', 'summary',
         Object
             .entries({
               orders_count: '订单数',
               receivable: '应收总额',
               paid: '模拟实收',
               refunded: '成功退款',
               pending: '待完成退款',
               failed: '退款失败',
               net: '净额'
             })
             .map(([k, title]) => {
               const v = groups.reduce((acc, g) => acc + Number(g[k]), 0);
               return el(
                   'div', 'kpi', el('p', 'k', title),
                   el('p', 'v', k === 'orders_count' ? String(v) : money(v)));
             })),
      table(
          ['订单状态', '数量'],
          states.map(s => [label(s), groups.find(g => g.status === s)?.orders_count ?? 0]))));
  const op = input('number', logFilter.operator), ord = input('number', logFilter.order),
        act = input('text', logFilter.action);
  const from = input('datetime-local', logFilter.fromText),
        until = input('datetime-local', logFilter.untilText);
  main.append(card(
      '操作审计',
      el('div', 'filters', field('操作者 ID', op), field('订单 ID', ord), field('动作代码', act),
         field('起始（北京时间）', from), field('截止（不含）', until),
         btn('查询日志',
             () => {
               logFilter = {
                 operator: op.value,
                 order: ord.value,
                 action: act.value,
                 fromText: from.value,
                 untilText: until.value,
                 from: from.value ? Date.parse(from.value + ':00+08:00') : '',
                 until: until.value ? Date.parse(until.value + ':00+08:00') : ''
               };
               refresh();
             })),
      table(
          ['服务器时间', '操作者', '目标', '动作', '前 → 后', '结果 / 原因', '请求号'],
          logs.map(
              l =>
                  [date(l.at_time), label(l.role) + ' ' + (l.operator_id ?? ''), l.target, l.action,
                   (l.before_state || '—') + ' → ' + (l.after_state || '—'),
                   label(l.result) + ' ' + l.reason, l.request_no])),
      actions(logFilter.offset ? btn('上一页', () => {
                logFilter.offset -= 50;
                refresh();
              }) : null, logs.length === 50 ? btn('下一页', () => {
                logFilter.offset = (logFilter.offset ?? 0) + 50;
                refresh();
              }) : null)));
}
