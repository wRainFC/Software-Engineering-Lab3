import {actions, api, btn, card, el, entry, field, go, input, label, modal, money, notify, para, qs, refresh, select, state, tag} from './ui.js';

let selectedShop;
let filter = {};
export async function browse(main) {
  const [shops, cart] = await Promise.all([api('/shops'), api('/cart')]);
  const shop = shops.find(s => s.id === selectedShop) ?? shops[0];
  if (!shop) {
    main.append(card('店铺列表', para('暂无店铺')));
    return;
  }
  selectedShop = shop.id;
  const dishes = await api('/dishes?' + qs({...filter, shop: shop.id}));
  const shopList = card(
      '店铺列表',
      shops.map(
          s =>
              entry(s.name, tag(s.status), actions(btn(s.id === shop.id ? '已选择' : '进店', () => {
                      selectedShop = s.id;
                      refresh();
                    }, s.id === shop.id ? 'tonal' : '')))));
  const key = input('search', filter.keyword), category = input('text', filter.category);
  const min = input('number', filter.min), max = input('number', filter.max);
  const sale = select([['', '全部'], ['true', '仅可售'], ['false', '不可售']], filter.sellable);
  const status = select(
      [['', '全部'], ['OPEN', '营业'], ['PAUSED', '停业'], ['CLOSED', '平台关闭']], filter.status);
  const menu = card(
      shop.name, para('服务区 ' + shop.areas + ' · 配送费 ' + money(shop.fee)),
      el('div', 'filters', field('关键词', key), field('分类', category),
         field('最低价（分）', min), field('最高价（分）', max), field('营业状态', status),
         field('可售状态', sale),
         btn('筛选',
             () => {
               filter = {
                 keyword: key.value,
                 category: category.value,
                 min: min.value,
                 max: max.value,
                 sellable: sale.value,
                 status: status.value
               };
               refresh();
             })),
      dishes.length ? el('div', 'grid', dishes.map(d => {
        const add = btn('加入购物车', async () => {
          const qty = Number(cart.find(x => x.id === d.id)?.quantity ?? 0) + 1;
          try {
            await api('/cart/' + d.id, 'POST', {quantity: qty, replace: false});
          } catch (e) {
            if (e.data?.code !== 'SWITCH_SHOP')
              throw e;
            if (!confirm('换店将清空原购物车，继续吗？'))
              return;
            await api('/cart/' + d.id, 'POST', {quantity: qty, replace: true});
          }
          refresh();
        }, 'tonal');
        add.disabled = !Number(d.sellable);
        return entry(
            d.name, para(d.category + ' · ' + money(d.price)),
            para(d.stock ? '库存 ' + d.stock : '售罄'), add);
      })) :
                      para('没有匹配的菜品'));
  main.append(
      el('div', 'split', shopList, menu),
      card(
          '单店购物车',
          para(
              cart.length ? cart[0].shop_name + ' · 小计 ' +
                      money(cart.reduce((v, i) => v + i.quantity * i.price, 0)) :
                            '购物车为空'),
          actions(btn('去结算', () => go('checkout'), 'pri'))));
}

function addressEditor(a = {}) {
  const contact = input('text', a.contact), phone = input('tel', a.phone),
        region = input('text', a.region), detail = input('text', a.detail);
  modal(
      a.id ? '编辑地址' : '新增地址',
      el('div', 'stack', field('联系人', contact), field('手机号', phone),
         field('服务区域', region), field('详细地址', detail)),
      async () => {
        await api('/addresses' + (a.id ? '/' + a.id : ''), 'POST', {
          contact: contact.value,
          phone: phone.value,
          region: region.value,
          detail: detail.value
        });
        refresh();
      });
}
const key = () => 'delivery.checkout.' + state.actor.id;
function keep(cmd) {
  localStorage.setItem(key(), JSON.stringify(cmd));
}
function finish(order) {
  localStorage.removeItem(key());
  notify('订单已创建 · ' + order.order_no);
  go('orders/' + order.id);
}
async function submit(cmd) {
  keep(cmd);
  let result;
  try {
    result = await api('/orders', 'POST', cmd);
  } catch (e) {
    if (e.data?.code === 'REQUOTE') {
      localStorage.removeItem(key());
      const q = e.data;
      modal(
          '确认新报价',
          el('div', 'stack', para('原合计 ' + money(q.oldTotal) + ' → 新合计 ' + money(q.total)),
             q.items.map(i => para(i.name + ' × ' + i.quantity + ' · ' + money(i.price))),
             para('配送费 ' + money(q.fee))),
          async () => {
            await submit({
              ...cmd,
              requestNo: crypto.randomUUID(),
              confirmedTotal: q.total,
              confirmedFee: q.fee,
              items: q.items.map(i => ({dishId: i.id, quantity: i.quantity, price: i.price}))
            });
          },
          '确认并重新下单');
      refresh();
      return;
    }
    if (e.status && e.status < 500) {
      localStorage.removeItem(key());
      refresh();
      throw e;
    }
    notify('结果待查询，请使用原请求号查询或重试');
    refresh();
    return;
  }
  if (result.id)
    finish(result);
  else {
    notify('请求处理中，请查询原请求');
    refresh();
  }
}
async function recover(cmd) {
  let result;
  try {
    result = await api('/orders/requests/' + encodeURIComponent(cmd.requestNo));
  } catch (e) {
    if (e.status !== 404)
      throw e;
  }
  if (result?.id)
    finish(result);
  else if (result?.state === 'PROCESSING')
    notify('请求仍在处理中，请稍后查询');
  else
    await submit(cmd);
}
export async function checkout(main) {
  const [cart, addresses, similar] =
      await Promise.all([api('/cart'), api('/addresses'), api('/cart/similar')]);
  if (similar.length)
    main.append(card(
        '已有相同菜品的未完成订单', para(similar[0].order_no),
        btn('查看该订单', () => go('orders/' + similar[0].id))));
  const saved = localStorage.getItem(key());
  if (saved) {
    const pending = JSON.parse(saved);
    main.append(card(
        '结果待查询', para('请求号 ' + pending.requestNo),
        btn('查询或重试原请求', () => recover(pending), 'pri')));
  }
  const goods = card('单店购物车', cart.length ? para(cart[0].shop_name) : para('购物车为空'));
  for (const i of cart) {
    const qty = input('number', i.quantity);
    qty.min = 1;
    qty.max = 99;
    goods.append(entry(
        i.name, para(money(i.price) + ' / 份'),
        actions(
            field('份数', qty),
            btn('更新',
                async () => {
                  await api('/cart/' + i.id, 'POST', {quantity: Number(qty.value), replace: false});
                  refresh();
                }),
            btn('移除',
                async () => {
                  await api('/cart/' + i.id, 'POST', {quantity: 0, replace: false});
                  refresh();
                })),
        (!i.shelf || i.stock < i.quantity || i.shop_status !== 'OPEN') ?
            para('当前不可结算，请修改购物车') :
            null));
  }
  const addr = card(
      '收货地址',
      addresses.map(
          a => entry(
              a.contact + ' · ' + a.phone, para(a.region + ' ' + a.detail),
              actions(
                  btn('编辑', () => addressEditor(a)),
                  btn('删除',
                      async () => {
                        if (confirm('删除这条收货地址？')) {
                          await api('/addresses/' + a.id, 'DELETE');
                          refresh();
                        }
                      })))),
      actions(btn('新增地址', () => addressEditor())));
  const selection =
      select(addresses.map(a => [a.id, a.contact + ' · ' + a.region + ' ' + a.detail]));
  const subtotal = cart.reduce((sum, i) => sum + i.quantity * i.price, 0), fee = cart[0]?.fee ?? 0;
  const confirmOrder = btn('提交订单并模拟付款', async () => {
    const cmd = {
      addressId: Number(selection.value),
      items: cart.map(i => ({dishId: i.id, quantity: i.quantity, price: i.price})),
      confirmedTotal: subtotal + fee,
      confirmedFee: fee,
      requestNo: crypto.randomUUID()
    };
    await submit(cmd);
  }, 'pri');
  function validate() {
    const a = addresses.find(a => a.id === Number(selection.value));
    const covered = a && cart[0]?.areas.split(/[,，、]/).some(s => s.trim() === a.region);
    confirmOrder.disabled = Boolean(saved) || !covered ||
        cart.some(i => !i.shelf || i.stock < i.quantity || i.shop_status !== 'OPEN');
    info.textContent = a && cart.length && !covered ? '该地址超出店铺服务区' : '';
  }
  const info = para('');
  selection.onchange = validate;
  validate();
  main.append(
      el('div', 'grid', el('div', 'stack', goods, addr),
         card(
             '确认下单', field('配送地址', selection), info, para('商品小计 ' + money(subtotal)),
             para('配送费 ' + money(fee)), el('h4', '', '合计 ' + money(subtotal + fee)),
             confirmOrder)));
}
