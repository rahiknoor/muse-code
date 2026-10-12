import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

// One line item. A record: compact class with name(), priceCents(), quantity().
// Money is stored as integer CENTS — never double — so totals always add up.
record Item(String name, int priceCents, int quantity) {
    int lineTotalCents() {
        return priceCents * quantity;
    }
}

public class Receipt {
    // Named constants instead of magic numbers (Claude's fix)
    static final double TAX_RATE = 0.08875; // simplified flat tax for this toy
    static final double DISCOUNT_RATE = 0.10;
    static final int DISCOUNT_THRESHOLD_CENTS = 2000; // $20.00
    static final long SEED = 42; // same seed = same cart every run (change it for a new one)

    /** Format cents as dollars. Locale.US so it prints $1.50, never $1,50. */
    static String money(int cents) {
        return String.format(Locale.US, "$%.2f", cents / 100.0);
    }

    public static void main(String[] args) {
        Random rand = new Random(SEED);

        // Fixed catalog: name -> price in cents. Picks are random;
        // duplicates merge into one line (same item, summed quantity).
        Map<String, Integer> catalog = new LinkedHashMap<>();
        catalog.put("Milk", 349);
        catalog.put("Bread", 299);
        catalog.put("Eggs", 429);
        catalog.put("Cheese", 549);
        catalog.put("Apples", 199);
        String[] names = catalog.keySet().toArray(new String[0]);

        Map<String, Item> cart = new LinkedHashMap<>();
        for (int i = 0; i < 3; i++) {
            String name = names[rand.nextInt(names.length)];
            int qty = rand.nextInt(5) + 1; // 1-5
            Item existing = cart.get(name);
            if (existing == null) {
                cart.put(name, new Item(name, catalog.get(name), qty));
            } else {
                cart.put(name, new Item(name, existing.priceCents(),
                        existing.quantity() + qty));
            }
        }

        System.out.println("=== Grocery Checkout ===\n");

        // Each item prints its line total as it is "scanned"
        for (Item item : cart.values()) {
            System.out.printf(Locale.US, "Scanned: %s x%d @ %s = %s%n%n",
                    item.name(), item.quantity(),
                    money(item.priceCents()), money(item.lineTotalCents()));
        }

        // All math in cents; round each step once, derive the total from parts
        int subtotal = 0;
        int itemCount = 0;
        for (Item item : cart.values()) {
            subtotal += item.lineTotalCents();
            itemCount += item.quantity();
        }

        int discount = 0;
        if (subtotal > DISCOUNT_THRESHOLD_CENTS) {
            discount = (int) Math.round(subtotal * DISCOUNT_RATE);
        }
        int afterDiscount = subtotal - discount;
        int tax = (int) Math.round(afterDiscount * TAX_RATE);
        int total = afterDiscount + tax;

        System.out.println("========== RECEIPT ==========");
        for (Item item : cart.values()) {
            System.out.printf(Locale.US, "%-15s x%-3d %8s%n",
                    item.name(), item.quantity(), money(item.lineTotalCents()));
        }
        System.out.println("------------------------------");
        System.out.println("Items:    " + itemCount);
        System.out.println("Subtotal: " + money(subtotal));
        if (discount > 0) {
            System.out.printf(Locale.US, "Discount (10%%): -%s%n", money(discount));
        }
        System.out.println("Tax:      " + money(tax));
        System.out.println("TOTAL:    " + money(total));
        System.out.println("==============================");
    }
}
