export type OrderStatus = 'PENDING_PAYMENT' | 'PAID' | 'ACCEPTED' | 'READY' | 'PICKED_UP' | 'DELIVERED' | 'CANCELLED' | 'REJECTED';

export interface DeliveryQuote { distanceM: number; feeCents: number; etaMinutes: number; deliverable: boolean }
export interface RestaurantView {
  id: number; slug: string; name: string; nameZh: string; description: string; cuisine: string; tags: string[];
  neighborhood: string; address: string; deliveryFeeCents: number; minOrderCents: number; prepMinutes: number;
  openNow: boolean; nextOpening?: string; acceptingOrders: boolean; hours: string; image?: string;
  lat?: number; lng?: number; deliveryRadiusKm: number; delivery?: DeliveryQuote; rating: number; ratingCount: number;
}
export interface DishView {
  id: number; name: string; nameZh: string; description: string; priceCents: number; tags: string[];
  spiceLevel: number; available: boolean; limited: boolean; stockRemaining?: number; image?: string;
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
  acceptingOrders: boolean; image?: string; distanceM?: number;
}
export interface Facet { value: string; count: number }
export interface SearchResult { total: number; hits: DishHit[]; facets: Record<string, Facet[]>; degraded: boolean }

export interface OrderLine { dishId: number; name: string; nameZh: string; unitPriceCents: number; quantity: number }
export interface OrderStep { version: number; status: OrderStatus; at: string; note?: string }
export interface OrderView {
  id: string; status: OrderStatus; version: number;
  restaurant: { id: number; slug: string; name: string; nameZh: string; address: string; lat?: number; lng?: number; image?: string };
  items: OrderLine[]; subtotalCents: number; deliveryFeeCents: number; totalCents: number; deliveryAddress: string;
  deliveryNotes: string; riderId?: number; customerName: string; cancelReason?: string; paymentDeadline: string;
  createdAt: string; updatedAt: string; checkoutUrl?: string; history: OrderStep[]; distanceM?: number;
  deliveryLat?: number; deliveryLng?: number; riderName?: string; review?: { rating: number; comment: string; reply?: string };
}
export interface StatusEvent {
  orderId: string; restaurantId: number; customerId: number; riderId?: number; status: OrderStatus;
  previous?: OrderStatus; version: number; at: string; cancelReason?: string;
}

export interface Page<T> { items: T[]; next?: string }
export interface Address {
  id: number; label: string; line1: string; line2: string; city: string; zip: string; lat: number; lng: number; notes: string;
}
export interface Place { zip: string; city: string; centroid: { lat: number; lng: number } }
export interface Review {
  orderId: string; restaurantId: number; author: string; rating: number; comment: string; createdAt: string;
  reply?: string; repliedAt?: string; hidden: boolean;
}
export interface RestaurantReviews { rating: { average: number; count: number }; page: Page<Review> }
export type RestaurantStatus = 'PENDING' | 'LIVE' | 'REJECTED' | 'SUSPENDED';
export interface RestaurantProfile {
  name: string; nameZh: string; description: string; cuisine: string; tags: string[]; neighborhood: string; address: string;
  zip: string; pinLat?: number; pinLng?: number; deliveryRadiusKm: number; deliveryFeeCents: number; minOrderCents: number;
  prepMinutes: number; opensAt: string; closesAt: string; open24h: boolean; timeZone: string;
}
export interface RestaurantSettings { id: number; slug: string; status: RestaurantStatus; statusNote?: string; version: number; profile: RestaurantProfile }
export interface RiderTracking { orderId: string; lat: number; lng: number; heading: 'restaurant' | 'customer'; distanceM: number; etaMinutes: number; at: string }
export interface DispatchOffer { id: number; orderId: string; riderId: number; status: string; distanceM: number; offeredAt: string; expiresAt: string }
