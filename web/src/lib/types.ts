export type OrderStatus = 'PENDING_PAYMENT' | 'PAID' | 'ACCEPTED' | 'READY' | 'PICKED_UP' | 'DELIVERED' | 'CANCELLED' | 'REJECTED';

export interface RestaurantView {
  id: number; slug: string; name: string; nameZh: string; description: string; cuisine: string; tags: string[];
  neighborhood: string; address: string; deliveryFeeCents: number; minOrderCents: number; prepMinutes: number;
  openNow: boolean; nextOpening?: string; acceptingOrders: boolean; hours: string;
}
export interface DishView {
  id: number; name: string; nameZh: string; description: string; priceCents: number; tags: string[];
  spiceLevel: number; available: boolean; limited: boolean; stockRemaining?: number;
}
export interface CategoryView { id: number; name: string; nameZh: string; dishes: DishView[] }
export interface HotDish { dishId: number; name: string; nameZh: string; priceCents: number; orders: number }
export interface MenuView { restaurant: RestaurantView; categories: CategoryView[]; popular: HotDish[] }
export interface TrendingDish {
  restaurantId: number; restaurantSlug: string; restaurantName: string; dishId: number; name: string; nameZh: string;
  priceCents: number; orders: number;
}
export interface DishHit {
  dishId: number; name: string; nameZh: string; description: string; priceCents: number; tags: string[]; spiceLevel: number;
  restaurantId: number; restaurantSlug: string; restaurantName: string; restaurantNameZh: string; cuisine: string;
  acceptingOrders: boolean;
}
export interface Facet { value: string; count: number }
export interface SearchResult { total: number; hits: DishHit[]; facets: Record<string, Facet[]>; degraded: boolean }

export interface OrderLine { dishId: number; name: string; nameZh: string; unitPriceCents: number; quantity: number }
export interface OrderStep { version: number; status: OrderStatus; at: string; note?: string }
export interface OrderView {
  id: string; status: OrderStatus; version: number;
  restaurant: { id: number; slug: string; name: string; nameZh: string; address: string };
  items: OrderLine[]; subtotalCents: number; deliveryFeeCents: number; totalCents: number; deliveryAddress: string;
  deliveryNotes: string; riderId?: number; customerName: string; cancelReason?: string; paymentDeadline: string;
  createdAt: string; updatedAt: string; checkoutUrl?: string; history: OrderStep[];
}
export interface StatusEvent {
  orderId: string; restaurantId: number; customerId: number; riderId?: number; status: OrderStatus;
  previous?: OrderStatus; version: number; at: string; cancelReason?: string;
}
