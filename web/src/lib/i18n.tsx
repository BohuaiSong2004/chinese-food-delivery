import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';

const en = {
  'skip': 'Skip to main content',
  'nav.home': 'Wokflow home', 'nav.search': 'Search', 'nav.orders': 'My orders', 'nav.kitchen': 'Kitchen',
  'nav.deliveries': 'Deliveries', 'nav.signIn': 'Sign in', 'nav.signOut': 'Sign out', 'nav.cart': 'Cart ({n})',
  'nav.primary': 'Main', 'lang.switch': '中文', 'lang.switchLabel': 'Switch to Chinese',
  'home.title': 'Regional Chinese food, wok to door',
  'home.subtitle': 'Sichuan, Cantonese, Lanzhou, Shanghai and more from San Gabriel Valley kitchens — tracked live.',
  'home.searchLabel': 'Search dishes', 'home.searchPlaceholder': 'Try “mapo tofu”, “xiaolongbao” or 牛肉面',
  'home.searchButton': 'Search', 'home.cuisines': 'Browse by cuisine', 'home.trending': 'Popular right now',
  'home.trendingEmpty': 'Nothing yet today — be the first to order.', 'home.restaurants': 'Restaurants',
  'home.orders': '{n} ordered in the last 24 h',
  'r.open': 'Open', 'r.closed': 'Closed', 'r.opensAt': 'Opens {time}', 'r.paused': 'Not taking orders',
  'r.fee': '{fee} delivery', 'r.prep': '~{min} min', 'r.min': '{amount} minimum', 'r.hours': 'Hours {hours}',
  'r.hours24': 'Open 24 hours',
  'menu.popular': 'Popular right now', 'menu.ordered': '{n} ordered in the last 24 h', 'menu.add': 'Add',
  'menu.addLabel': 'Add {name} to cart', 'menu.soldOut': 'Sold out', 'menu.left': '{n} left today',
  'menu.unavailable': 'Unavailable', 'menu.spice': 'Spice level {n} of 3',
  'menu.newCart': 'Your cart has dishes from {name}. Start a new cart for this restaurant?',
  'menu.startNew': 'Start new cart', 'menu.keep': 'Keep current cart', 'menu.viewCart': 'View cart · {n} items · {total}',
  'menu.closedNotice': 'This kitchen is closed right now. You can browse the menu; ordering opens at {time}.',
  'search.title': 'Search', 'search.label': 'Dish, restaurant, Chinese or pinyin', 'search.results': '{n} dishes',
  'search.none': 'No dishes match. Try fewer filters or another spelling.',
  'search.degraded': 'Search is running in a simplified mode right now: pinyin and filters may be limited.',
  'search.filters': 'Filters', 'search.tags': 'Dietary & style', 'search.cuisine': 'Cuisine', 'search.any': 'Any',
  'search.maxSpice': 'Maximum spice', 'search.spice0': 'Not spicy', 'search.spice1': 'Mild', 'search.spice2': 'Medium',
  'search.spice3': 'Hot', 'search.clear': 'Clear filters', 'search.at': 'at {name}',
  'cart.title': 'Your cart', 'cart.empty': 'Your cart is empty.', 'cart.browse': 'Browse restaurants',
  'cart.from': 'From {name}', 'cart.subtotal': 'Subtotal', 'cart.delivery': 'Delivery', 'cart.total': 'Total',
  'cart.address': 'Delivery address', 'cart.notes': 'Notes for the kitchen or rider (optional)',
  'cart.place': 'Place order and pay', 'cart.placing': 'Placing order…', 'cart.signInFirst': 'Sign in to place your order',
  'cart.belowMin': 'Add {amount} more to reach the minimum order.', 'cart.remove': 'Remove {name}',
  'cart.increase': 'One more {name}', 'cart.decrease': 'One less {name}', 'cart.qty': 'Quantity of {name}',
  'order.title': 'Order {id}', 'order.live': 'Live', 'order.reconnecting': 'Reconnecting…',
  'order.waiting': 'Waiting for the payment provider to confirm…', 'order.payNow': 'Pay now',
  'order.payBy': 'Pay by {time} or the order is released.', 'order.cancel': 'Cancel order',
  'order.cancelled': 'Cancelled: {reason}', 'order.rejected': 'The restaurant could not take this order: {reason}',
  'order.refunded': 'Your payment is being refunded.', 'order.items': 'Items', 'order.deliverTo': 'Deliver to',
  'order.timeline': 'Progress', 'order.updated': 'Order status: {status}',
  'status.PENDING_PAYMENT': 'Awaiting payment', 'status.PAID': 'Paid — waiting for the kitchen',
  'status.ACCEPTED': 'Being prepared', 'status.READY': 'Ready for pickup', 'status.PICKED_UP': 'On the way',
  'status.DELIVERED': 'Delivered', 'status.CANCELLED': 'Cancelled', 'status.REJECTED': 'Rejected',
  'step.placed': 'Placed', 'step.paid': 'Paid', 'step.accepted': 'Preparing', 'step.ready': 'Ready',
  'step.picked': 'On the way', 'step.delivered': 'Delivered',
  'orders.title': 'My orders', 'orders.none': 'No orders yet.',
  'auth.signIn': 'Sign in', 'auth.signUp': 'Create account', 'auth.email': 'Email', 'auth.password': 'Password',
  'auth.passwordHint': 'At least 10 characters.', 'auth.name': 'Your name', 'auth.noAccount': 'New here? Create an account',
  'auth.haveAccount': 'Already have an account? Sign in', 'auth.working': 'Please wait…',
  'auth.demo': 'Demo accounts (password “wokflow-demo”):',
  'merchant.title': 'Kitchen', 'merchant.restaurant': 'Restaurant', 'merchant.board': 'Orders', 'merchant.menu': 'Menu',
  'merchant.earnings': 'Earnings', 'merchant.pause': 'Pause new orders', 'merchant.resume': 'Resume orders',
  'merchant.pausedNotice': 'Paused: customers cannot place new orders.',
  'merchant.col.PAID': 'New', 'merchant.col.ACCEPTED': 'Preparing', 'merchant.col.READY': 'Ready',
  'merchant.col.PICKED_UP': 'Out for delivery', 'merchant.col.done': 'Finished today',
  'merchant.accept': 'Accept', 'merchant.reject': 'Reject', 'merchant.ready': 'Mark ready', 'merchant.empty': 'None',
  'merchant.rejectReason': 'Reason (shown to the customer)', 'merchant.confirmReject': 'Reject and refund',
  'merchant.dish': 'Dish', 'merchant.price': 'Price (USD)', 'merchant.available': 'Available',
  'merchant.dailyStock': 'Daily limit', 'merchant.left': 'Left today', 'merchant.save': 'Save', 'merchant.saved': 'Saved',
  'merchant.stale': 'Changed elsewhere — reloaded the latest values.', 'merchant.balance': 'Balance',
  'merchant.availableToPay': 'Available for payout', 'merchant.pending': 'Held for pending refunds',
  'merchant.payout': 'Request payout', 'merchant.payoutAmount': 'Amount (USD)', 'merchant.payoutDone': 'Payout of {amount} recorded.',
  'merchant.entries': 'Recent ledger entries', 'merchant.entry.CAPTURE': 'Order paid', 'merchant.entry.REFUND': 'Refund',
  'merchant.entry.PAYOUT': 'Payout', 'merchant.when': 'When', 'merchant.what': 'Entry', 'merchant.amount': 'Amount',
  'merchant.order': 'Order', 'merchant.none': 'You are not staff of any restaurant.',
  'rider.title': 'Deliveries', 'rider.jobs': 'Open jobs', 'rider.mine': 'My deliveries', 'rider.claim': 'Take this job',
  'rider.pickup': 'Picked up', 'rider.deliver': 'Delivered', 'rider.noJobs': 'No open jobs right now.',
  'rider.noMine': 'Nothing assigned to you.', 'rider.from': 'Pick up at {name}', 'rider.to': 'Drop off at {address}',
  'rider.waitingKitchen': 'Kitchen is still preparing',
  'common.loading': 'Loading…', 'common.error': 'Something went wrong: {message}', 'common.retry': 'Try again',
  'common.notFound': 'Page not found', 'common.home': 'Back to home',
};

type Dict = Record<keyof typeof en, string>;

const zh: Dict = {
  'skip': '跳到主要内容',
  'nav.home': 'Wokflow 首页', 'nav.search': '搜索', 'nav.orders': '我的订单', 'nav.kitchen': '商家后台',
  'nav.deliveries': '配送', 'nav.signIn': '登录', 'nav.signOut': '退出', 'nav.cart': '购物车（{n}）',
  'nav.primary': '主导航', 'lang.switch': 'English', 'lang.switchLabel': '切换到英文',
  'home.title': '地道中餐，锅气直达家门',
  'home.subtitle': '川菜、粤菜、兰州拉面、上海菜……圣盖博谷的好馆子，订单进度实时可见。',
  'home.searchLabel': '搜索菜品', 'home.searchPlaceholder': '试试“麻婆豆腐”、“xiaolongbao”或 mpdf',
  'home.searchButton': '搜索', 'home.cuisines': '按菜系浏览', 'home.trending': '今日热门',
  'home.trendingEmpty': '今天还没有订单，来做第一个下单的人吧。', 'home.restaurants': '餐厅',
  'home.orders': '近 24 小时已点 {n} 份',
  'r.open': '营业中', 'r.closed': '休息中', 'r.opensAt': '{time} 开门', 'r.paused': '暂停接单',
  'r.fee': '配送费 {fee}', 'r.prep': '约 {min} 分钟', 'r.min': '起送 {amount}', 'r.hours': '营业时间 {hours}',
  'r.hours24': '24 小时营业',
  'menu.popular': '今日热门', 'menu.ordered': '近 24 小时已点 {n} 份', 'menu.add': '加入',
  'menu.addLabel': '把{name}加入购物车', 'menu.soldOut': '已售罄', 'menu.left': '今日剩 {n} 份',
  'menu.unavailable': '暂不供应', 'menu.spice': '辣度 {n}/3',
  'menu.newCart': '购物车里是{name}的菜。要为这家餐厅重新开一个购物车吗？',
  'menu.startNew': '重新开始', 'menu.keep': '保留原购物车', 'menu.viewCart': '查看购物车 · {n} 件 · {total}',
  'menu.closedNotice': '这家店现在休息，可以先看菜单；{time} 开始接单。',
  'search.title': '搜索', 'search.label': '菜名、餐厅、中文或拼音', 'search.results': '共 {n} 道菜',
  'search.none': '没有找到匹配的菜品，试试减少筛选条件或换个写法。',
  'search.degraded': '搜索目前处于简化模式：拼音和筛选功能可能受限。',
  'search.filters': '筛选', 'search.tags': '饮食与风格', 'search.cuisine': '菜系', 'search.any': '不限',
  'search.maxSpice': '最高辣度', 'search.spice0': '不辣', 'search.spice1': '微辣', 'search.spice2': '中辣',
  'search.spice3': '特辣', 'search.clear': '清除筛选', 'search.at': '来自{name}',
  'cart.title': '购物车', 'cart.empty': '购物车是空的。', 'cart.browse': '去看看餐厅',
  'cart.from': '来自{name}', 'cart.subtotal': '小计', 'cart.delivery': '配送费', 'cart.total': '合计',
  'cart.address': '送餐地址', 'cart.notes': '给厨房或骑手的备注（选填）',
  'cart.place': '下单并付款', 'cart.placing': '正在下单…', 'cart.signInFirst': '登录后下单',
  'cart.belowMin': '再点 {amount} 即可起送。', 'cart.remove': '移除{name}',
  'cart.increase': '{name}加一份', 'cart.decrease': '{name}减一份', 'cart.qty': '{name}的数量',
  'order.title': '订单 {id}', 'order.live': '实时', 'order.reconnecting': '正在重新连接…',
  'order.waiting': '正在等待支付平台确认…', 'order.payNow': '去付款',
  'order.payBy': '请在 {time} 前完成付款，否则订单会自动取消。', 'order.cancel': '取消订单',
  'order.cancelled': '已取消：{reason}', 'order.rejected': '餐厅无法接这一单：{reason}',
  'order.refunded': '款项正在退回。', 'order.items': '菜品', 'order.deliverTo': '送至',
  'order.timeline': '进度', 'order.updated': '订单状态：{status}',
  'status.PENDING_PAYMENT': '待付款', 'status.PAID': '已付款，等待商家接单',
  'status.ACCEPTED': '制作中', 'status.READY': '待取餐', 'status.PICKED_UP': '配送中',
  'status.DELIVERED': '已送达', 'status.CANCELLED': '已取消', 'status.REJECTED': '已拒单',
  'step.placed': '已下单', 'step.paid': '已付款', 'step.accepted': '制作中', 'step.ready': '待取餐',
  'step.picked': '配送中', 'step.delivered': '已送达',
  'orders.title': '我的订单', 'orders.none': '还没有订单。',
  'auth.signIn': '登录', 'auth.signUp': '注册账号', 'auth.email': '邮箱', 'auth.password': '密码',
  'auth.passwordHint': '至少 10 个字符。', 'auth.name': '你的名字', 'auth.noAccount': '第一次来？注册账号',
  'auth.haveAccount': '已有账号？登录', 'auth.working': '请稍候…',
  'auth.demo': '演示账号（密码 “wokflow-demo”）：',
  'merchant.title': '商家后台', 'merchant.restaurant': '餐厅', 'merchant.board': '订单', 'merchant.menu': '菜单',
  'merchant.earnings': '收入', 'merchant.pause': '暂停接单', 'merchant.resume': '恢复接单',
  'merchant.pausedNotice': '已暂停：顾客暂时无法下新订单。',
  'merchant.col.PAID': '新订单', 'merchant.col.ACCEPTED': '制作中', 'merchant.col.READY': '待取餐',
  'merchant.col.PICKED_UP': '配送中', 'merchant.col.done': '今日已完成',
  'merchant.accept': '接单', 'merchant.reject': '拒单', 'merchant.ready': '出餐', 'merchant.empty': '暂无',
  'merchant.rejectReason': '原因（会显示给顾客）', 'merchant.confirmReject': '拒单并退款',
  'merchant.dish': '菜品', 'merchant.price': '价格（美元）', 'merchant.available': '供应',
  'merchant.dailyStock': '每日限量', 'merchant.left': '今日剩余', 'merchant.save': '保存', 'merchant.saved': '已保存',
  'merchant.stale': '这道菜在别处被修改过，已载入最新数据。', 'merchant.balance': '账户余额',
  'merchant.availableToPay': '可提现', 'merchant.pending': '待退款冻结',
  'merchant.payout': '申请提现', 'merchant.payoutAmount': '金额（美元）', 'merchant.payoutDone': '已记录 {amount} 的提现。',
  'merchant.entries': '最近账目', 'merchant.entry.CAPTURE': '订单收款', 'merchant.entry.REFUND': '退款',
  'merchant.entry.PAYOUT': '提现', 'merchant.when': '时间', 'merchant.what': '类型', 'merchant.amount': '金额',
  'merchant.order': '订单', 'merchant.none': '你还不是任何餐厅的员工。',
  'rider.title': '配送', 'rider.jobs': '待接订单', 'rider.mine': '我的配送', 'rider.claim': '接这一单',
  'rider.pickup': '已取餐', 'rider.deliver': '已送达', 'rider.noJobs': '暂时没有待接订单。',
  'rider.noMine': '你还没有配送任务。', 'rider.from': '到{name}取餐', 'rider.to': '送至 {address}',
  'rider.waitingKitchen': '厨房还在制作',
  'common.loading': '加载中…', 'common.error': '出错了：{message}', 'common.retry': '重试',
  'common.notFound': '页面不存在', 'common.home': '返回首页',
};

const labels: Record<'en' | 'zh', Record<string, string>> = {
  en: {
    sichuan: 'Sichuan', cantonese: 'Cantonese', dongbei: 'Dongbei', northwestern: 'Northwestern', shaanxi: 'Shaanxi',
    shanghainese: 'Shanghainese', hunan: 'Hunan', taiwanese: 'Taiwanese', test: 'Test',
    spicy: 'Spicy', vegetarian: 'Vegetarian', vegan: 'Vegan', halal: 'Halal', signature: 'Signature', dumplings: 'Dumplings',
    noodles: 'Noodles', soup: 'Soup', rice: 'Rice', seafood: 'Seafood', 'dim-sum': 'Dim sum', congee: 'Congee',
    drink: 'Drinks', dessert: 'Dessert', 'gluten-free': 'Gluten-free', grill: 'Grill', bbq: 'BBQ', tofu: 'Tofu',
    peanuts: 'Contains peanuts', 'late-night': 'Late night',
  },
  zh: {
    sichuan: '川菜', cantonese: '粤菜', dongbei: '东北菜', northwestern: '西北菜', shaanxi: '陕西菜',
    shanghainese: '上海菜', hunan: '湘菜', taiwanese: '台湾菜', test: '测试',
    spicy: '辣', vegetarian: '素食', vegan: '纯素', halal: '清真', signature: '招牌', dumplings: '饺子',
    noodles: '面食', soup: '汤', rice: '饭', seafood: '海鲜', 'dim-sum': '点心', congee: '粥',
    drink: '饮品', dessert: '甜品', 'gluten-free': '无麸质', grill: '烧烤', bbq: '烧腊', tofu: '豆腐',
    peanuts: '含花生', 'late-night': '夜宵',
  },
};

export type Lang = 'en' | 'zh';
export type Key = keyof typeof en;
const dicts: Record<Lang, Dict> = { en, zh };

interface I18n {
  lang: Lang;
  setLang: (l: Lang) => void;
  t: (key: Key, vars?: Record<string, string | number>) => string;
  label: (value: string) => string;
  /** Primary and secondary names for something that has both. */
  names: (o: { name: string; nameZh: string }) => [string, string];
}

const Ctx = createContext<I18n | null>(null);

function initialLang(): Lang {
  try {
    const saved = localStorage.getItem('wf_lang');
    if (saved === 'en' || saved === 'zh') return saved;
  } catch { /* storage unavailable */ }
  return navigator.language?.toLowerCase().startsWith('zh') ? 'zh' : 'en';
}

export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<Lang>(initialLang);
  useEffect(() => {
    document.documentElement.lang = lang === 'zh' ? 'zh-CN' : 'en';
  }, [lang]);
  const setLang = useCallback((l: Lang) => {
    setLangState(l);
    try { localStorage.setItem('wf_lang', l); } catch { /* ignore */ }
  }, []);
  const value = useMemo<I18n>(() => ({
    lang,
    setLang,
    t: (key, vars) => {
      let s = dicts[lang][key] ?? key;
      if (vars) for (const [k, v] of Object.entries(vars)) s = s.replaceAll(`{${k}}`, String(v));
      return s;
    },
    label: (v) => labels[lang][v] ?? v,
    names: (o) => (lang === 'zh' ? [o.nameZh, o.name] : [o.name, o.nameZh]),
  }), [lang, setLang]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useI18n(): I18n {
  const v = useContext(Ctx);
  if (!v) throw new Error('useI18n outside I18nProvider');
  return v;
}

export const dictionaries = dicts;
