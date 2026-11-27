import {actions, api, btn, card, date, el, entry, field, go, input, label, modal, money, notify, para, qs, refresh, refundLabel, select, state, states, table, tag} from './ui.js';

const filters = new Map();
function queryKey(view) {
  return state.actor.id + '/' + view;
}
export function filterBox(view, shops, includeOrder = true) {
  const key = queryKey(view), old = filters.get(key) ?? {};
  const inputs = {
    shop: select([['', '全部店铺'], ...shops.map(s => [s.id, s.name])], old.shop),
    from: input('datetime-local', old.from),
    until: input('datetime-local', old.until)
  };
  if (includeOrder)
    Object.assign(inputs, {
      number: input('text', old.number),
      status: select([['', '全部状态'], ...states.map(s => [s, label(s)])], old.status),
      min: input('number', old.min),
      max: input('number', old.max)
    });
  const titles = {
    shop: '店铺',
    from: '起始时间（北京时间）',
    until: '截止时间（不含）',
    number: '订单号',
    status: '订单状态',
    min: '最低金额（分）',
    max: '最高金额（分）'
  };
  return el(
      'div', 'filters', Object.entries(inputs).map(([k, i]) => field(titles[k], i)),
      btn('查询', () => {
        filters.set(key, Object.fromEntries(Object.entries(inputs).map(([k, i]) => [k, i.value])));
        refresh();
      }, 'tonal'));
}
export function query(view) {
  const data = {...filters.get(queryKey(view))};
  for (const name of ['from', 'until'])
    if (data[name])
      data[name] = Date.parse(data[name] + ':00+08:00');
  return qs(data);
}
function pager(view, rows) {
  const key = queryKey(view), data = filters.get(key) ?? {}, offset = data.offset ?? 0;
  const move = n => {
    filters.set(key, {...data, offset: n});
    refresh();
  };
  return actions(
      offset ? btn('上一页', () => move(offset - 50)) : null,
      rows.length === 50 ? btn('下一页', () => move(offset + 50)) : null);
}
export async function orderList(main, view) {
  const [shops, rows] = await Promise.all([api('/shops'), api('/orders?' + query(view))]);
  main.append(card('订单筛选', filterBox(view, shops)));
  const row = o => entry(
      o.shop_name, para(o.order_no), para(tag(o.status), ' · ' + money(o.total)),
      para(date(o.created_at) + (o.refund_status ? ' · ' + refundLabel(o.refund_status) : '')),
      o.pending_cancel ? para('取消申请待处理') : null,
      o.status === 'WAITING' ? para('接单截止 ' + date(o.deadline)) : null,
      btn('查看与处理', () => go(view + '/' + o.id), 'tonal'));
  if (view === 'board') {
    main.append(
        el('div', 'grid',
           states.filter(s => rows.some(o => o.status === s))
               .map(s => card(label(s), rows.filter(o => o.status === s).map(row)))));
  } else
    main.append(el('div', 'grid', rows.map(o => card('订单 #' + o.id, row(o)))));
  if (!rows.length)
    main.append(para('当前条件下没有订单'));
  main.append(pager(view, rows));
}

async function send(o, action, extra) {
  const key = 'delivery.action.' + state.actor.id + '.' + o.id + '.' + action;
  const body = {action, version: o.version, reason: '', riderId: null, taskId: null, ...extra};
  const signature = JSON.stringify(body);
  const saved = JSON.parse(sessionStorage.getItem(key) ?? 'null');
  const requestNo = saved?.signature === signature ? saved.requestNo : crypto.randomUUID();
  sessionStorage.setItem(key, JSON.stringify({signature, requestNo}));
  await api('/orders/' + o.id + '/actions', 'POST', {...body, requestNo});
  sessionStorage.removeItem(key);
  notify('已处理');
  refresh();
}
function operate(o, action, extra = {}) {
  if (['REQUEST_CANCEL', 'DECLINE', 'REJECT', 'ABORT', 'EXCEPTION'].includes(action)) {
    const reason = input();
    modal(
        action === 'EXCEPTION' ? '上报配送异常' : label(action), field('原因或详细说明', reason),
        () => send(o, action, {...extra, reason: reason.value}), '提交');
    return;
  }
  if (['CANCEL', 'APPROVE'].includes(action) && !confirm('确认取消这笔订单并创建全额模拟退款？'))
    return;
  return send(o, action, extra);
}
function controls(o, users) {
  const buttons = [], role = state.actor.role, s = o.status;
  const add = (a, title) => buttons.push(btn(title ?? label(a), () => operate(o, a), 'tonal'));
  if (role === 'CUSTOMER') {
    if (s === 'WAITING')
      add('CANCEL');
    if (s === 'ACCEPTED' && !o.requests.some(r => r.status === 'PENDING'))
      add('REQUEST_CANCEL');
    if (s === 'DELIVERED')
      add('CONFIRM');
  }
  if (role === 'MERCHANT') {
    if (s === 'WAITING') {
      if (Date.now() < o.deadline)
        add('ACCEPT');
      add('REJECT');
    }
    if (s === 'ACCEPTED') {
      if (o.requests.some(r => r.status === 'PENDING')) {
        add('APPROVE');
        add('DECLINE');
      }
      add('COOK');
    }
    if (s === 'COOKING')
      add('READY', '备餐完成');
  }
  if (role === 'ADMIN') {
    if (s === 'READY') {
      const riders = users.filter(u => u.role === 'RIDER' && u.enabled);
      const list = select(riders.map(u => [u.id, u.account]));
      buttons.push(
          field('配送员', list),
          btn('分配 / 改派', () => send(o, 'ASSIGN', {riderId: Number(list.value)}), 'pri'));
    }
    if (s === 'EXCEPTION')
      add('RESTORE');
    if (['READY', 'EXCEPTION', 'COOKING', 'DELIVERING'].includes(s))
      add('ABORT');
    if (o.refund_status === 'FAILED')
      add('RETRY_REFUND');
  }
  return actions(...buttons);
}
export async function orderDetail(main, view, id) {
  const [o, users] = await Promise.all(
      [api('/orders/' + id), state.actor.role === 'ADMIN' ? api('/admin/users') : []]);
  main.append(
      btn('返回订单列表', () => go(view)),
      card(
          o.shop_name, para(o.order_no, ' · ', tag(o.status)), para('下单 ' + date(o.created_at)),
          o.status === 'WAITING' ? para('接单截止 ' + date(o.deadline)) : null,
          para(o.contact + ' · ' + o.phone + ' · ' + o.address),
          table(
              ['成交菜品', '单价', '数量', '小计'],
              o.items.map(i => [i.name, money(i.price), i.quantity, money(i.price * i.quantity)])),
          para('商品 ' + money(o.items_total) + ' + 配送 ' + money(o.fee) + ' = ' + money(o.total)),
          para('模拟付款号 ' + o.pay_no),
          o.refund_no ? para(
                            '全额模拟退款 ' + money(o.total) + ' · ' +
                            refundLabel(o.refund_status) + ' · ' + o.refund_no) :
                        null,
          o.refund_reason ? para(o.refund_reason) : null, controls(o, users)));
  if (o.requests.length)
    main.append(card(
        '取消申请', o.requests.map(r => entry(label(r.status), para(r.reason), para(r.reply)))));
  if (o.tasks.length)
    main.append(card(
        '配送记录',
        o.tasks.map(
            t => entry(
                t.rider + ' · ' + label(t.status), para(date(t.assigned_at)), para(t.reason)))));
  main.append(card(
      '履约时间线',
      o.events.map(
          e => para(
              date(e.at_time) + ' · ' + label(e.action) + ' → ' + label(e.after_state) + ' ' +
              e.reason))));
}
export async function tasks(main) {
  const rows = await api('/tasks');
  for (const [title, active] of [['当前任务', true], ['历史任务', false]]) {
    const group = rows.filter(t => ['ASSIGNED', 'PICKED'].includes(t.status) === active);
    main.append(card(
        title,
        group.length ? group.map(t => {
          const o = {id: t.order_id, version: t.version};
          const button = (action, title) =>
              btn(title, () => operate(o, action, {taskId: t.id}), 'tonal');
          return entry(
              t.shop_name + ' · ' + label(t.status), para(t.order_no), para(date(t.assigned_at)),
              t.reason ? para(t.reason) : null,
              active ? para(
                           '店铺 ' + t.shop_phone + ' · ' + t.contact + ' ' + t.phone + ' · ' +
                           t.address) :
                       null,
              actions(
                  t.status === 'ASSIGNED' ? button('PICK', '确认取餐') : null,
                  t.status === 'PICKED' ?
                      [button('DELIVER', '确认送达'), button('EXCEPTION', '上报异常')] :
                      null));
        }) :
                       para('暂无任务')));
  }
}
