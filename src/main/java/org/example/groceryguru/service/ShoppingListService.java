package org.example.groceryguru.service;

import jakarta.persistence.EntityNotFoundException;
import org.example.groceryguru.dto.ItemPriceDetail;
import org.example.groceryguru.dto.StoreOptimizationResult;
import org.example.groceryguru.model.*;
import org.example.groceryguru.repository.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;

@Service
public class ShoppingListService {

    private final ShoppingListRepo shoppingListRepo;
    private final ShoppingListItemRepo shoppingListItemRepo;
    private final ShoppingListMemberRepo memberRepo;
    private final ProductRepo productRepo;
    private final UserRepo userRepo;
    private final PriceRepo priceRepo;
    private final JdbcTemplate jdbc;
    private final ListNotificationService notifier;
    private final ProductTranslationService translationService;

    public ShoppingListService(ShoppingListRepo shoppingListRepo,
                               ShoppingListItemRepo shoppingListItemRepo,
                               ShoppingListMemberRepo memberRepo,
                               ProductRepo productRepo,
                               UserRepo userRepo,
                               PriceRepo priceRepo,
                               JdbcTemplate jdbc,
                               ListNotificationService notifier,
                               ProductTranslationService translationService) {
        this.shoppingListRepo = shoppingListRepo;
        this.shoppingListItemRepo = shoppingListItemRepo;
        this.memberRepo = memberRepo;
        this.productRepo = productRepo;
        this.userRepo = userRepo;
        this.priceRepo = priceRepo;
        this.jdbc = jdbc;
        this.notifier = notifier;
        this.translationService = translationService;
    }

    public List<ShoppingList> getListsForUser(Long userId) {
        return shoppingListRepo.findOwnedOrShared(userId);
    }

    // --- Sharing ---
    public ShoppingListMember shareList(Long listId, String email, Long requestingUserId) {
        ShoppingList list = shoppingListRepo.findById(listId)
                .orElseThrow(() -> new EntityNotFoundException("List not found: " + listId));
        if (!list.getUser().getId().equals(requestingUserId)) {
            throw new IllegalStateException("Only the owner can share this list");
        }
        User target = userRepo.findByEmail(email)
                .orElseThrow(() -> new EntityNotFoundException("No user found with email: " + email));
        if (target.getId().equals(requestingUserId)) {
            throw new IllegalStateException("Cannot share list with yourself");
        }
        if (memberRepo.existsByShoppingListIdAndUserId(listId, target.getId())) {
            throw new IllegalStateException("User is already a member of this list");
        }
        ShoppingListMember member = new ShoppingListMember();
        member.setShoppingList(list);
        member.setUser(target);
        member.setRole("MEMBER");
        ShoppingListMember saved = memberRepo.save(member);
        notifier.notifyListChanged(listId, "MEMBER_ADDED", saved);
        return saved;
    }

    public List<ShoppingListMember> getMembers(Long listId) {
        return memberRepo.findByShoppingListId(listId);
    }

    public void removeMember(Long listId, Long memberId, Long requestingUserId) {
        ShoppingList list = shoppingListRepo.findById(listId)
                .orElseThrow(() -> new EntityNotFoundException("List not found: " + listId));
        ShoppingListMember member = memberRepo.findById(memberId)
                .orElseThrow(() -> new EntityNotFoundException("Member not found: " + memberId));
        // Owner can remove anyone; members can only remove themselves
        boolean isOwner = list.getUser().getId().equals(requestingUserId);
        boolean isSelf = member.getUser().getId().equals(requestingUserId);
        if (!isOwner && !isSelf) {
            throw new IllegalStateException("Not authorized to remove this member");
        }
        memberRepo.delete(member);
        notifier.notifyListChanged(listId, "MEMBER_REMOVED", memberId);
    }

    public boolean canAccessList(Long listId, Long userId) {
        ShoppingList list = shoppingListRepo.findById(listId).orElse(null);
        if (list == null) return false;
        if (list.getUser().getId().equals(userId)) return true;
        return memberRepo.existsByShoppingListIdAndUserId(listId, userId);
    }

    public boolean isOwner(Long listId, Long userId) {
        ShoppingList list = shoppingListRepo.findById(listId).orElse(null);
        return list != null && list.getUser().getId().equals(userId);
    }

    /** Resolves the list an item belongs to (for item-level access checks). */
    public Long getListIdForItem(Long itemId) {
        return shoppingListItemRepo.findById(itemId)
                .map(i -> i.getShoppingList().getId())
                .orElseThrow(() -> new EntityNotFoundException("Item not found: " + itemId));
    }

    /** Maps an authenticated email (JWT principal) to the user's id. */
    public Long userIdForEmail(String email) {
        return userRepo.findByEmail(email)
                .map(User::getId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + email));
    }

    public ShoppingList createList(String name, Long userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new EntityNotFoundException("User not found: " + userId));
        ShoppingList list = new ShoppingList();
        list.setName(name);
        list.setUser(user);
        return shoppingListRepo.save(list);
    }

    public ShoppingListItem addItem(Long listId, Long productId, Integer quantity) {
        ShoppingList list = shoppingListRepo.findById(listId)
                .orElseThrow(() -> new EntityNotFoundException("List not found: " + listId));
        Product product = productRepo.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("Product not found: " + productId));
        ShoppingListItem item = new ShoppingListItem();
        item.setShoppingList(list);
        item.setProduct(product);
        item.setQuantity(quantity);
        ShoppingListItem saved = shoppingListItemRepo.save(item);
        notifier.notifyListChanged(listId, "ITEM_ADDED", saved);
        return saved;
    }

    public ShoppingListItem addGenericItem(Long listId, String searchTerm, Integer quantity) {
        ShoppingList list = shoppingListRepo.findById(listId)
                .orElseThrow(() -> new EntityNotFoundException("List not found: " + listId));
        ShoppingListItem item = new ShoppingListItem();
        item.setShoppingList(list);
        item.setSearchTerm(searchTerm);
        item.setQuantity(quantity);
        ShoppingListItem saved = shoppingListItemRepo.save(item);
        notifier.notifyListChanged(listId, "ITEM_ADDED", saved);
        return saved;
    }

    public ShoppingListItem updateItemQuantity(Long itemId, Integer quantity) {
        ShoppingListItem item = shoppingListItemRepo.findById(itemId)
                .orElseThrow(() -> new EntityNotFoundException("Item not found: " + itemId));
        item.setQuantity(quantity);
        ShoppingListItem saved = shoppingListItemRepo.save(item);
        notifier.notifyListChanged(item.getShoppingList().getId(), "ITEM_UPDATED", saved);
        return saved;
    }

    public void removeItem(Long itemId) {
        ShoppingListItem item = shoppingListItemRepo.findById(itemId)
                .orElseThrow(() -> new EntityNotFoundException("Item not found: " + itemId));
        Long listId = item.getShoppingList().getId();
        shoppingListItemRepo.deleteById(itemId);
        notifier.notifyListChanged(listId, "ITEM_REMOVED", itemId);
    }

    public void deleteList(Long listId) {
        if (!shoppingListRepo.existsById(listId))
            throw new EntityNotFoundException("List not found: " + listId);
        shoppingListRepo.deleteById(listId);
    }

    public List<StoreOptimizationResult> optimize(Long listId, String city) {
        List<ShoppingListItem> items = shoppingListItemRepo.findByShoppingListId(listId);
        if (items.isEmpty()) throw new IllegalStateException("Shopping list is empty");

        // Separate specific products from generic search terms
        List<ShoppingListItem> specificItems = new ArrayList<>();
        List<ShoppingListItem> genericItems = new ArrayList<>();
        for (ShoppingListItem item : items) {
            if (item.getProduct() != null) {
                specificItems.add(item);
            } else if (item.getSearchTerm() != null && !item.getSearchTerm().isBlank()) {
                genericItems.add(item);
            }
        }

        // For generic items, find matching product IDs per chain using a keyword search
        // itemLabel -> { chainName -> (productId, price, matchedProductName) }
        Map<String, Integer> itemQuantities = new LinkedHashMap<>();
        Map<String, Map<String, GenericMatch>> genericMatches = new LinkedHashMap<>();

        for (ShoppingListItem gi : genericItems) {
            String term = gi.getSearchTerm();
            itemQuantities.put("generic_" + gi.getId(), gi.getQuantity());

            List<Object> gParams = new ArrayList<>();
            Set<String> expandedTerms = translationService.expandSearch(term);
            StringBuilder likeClause = new StringBuilder();
            for (String t : expandedTerms) {
                if (!likeClause.isEmpty()) likeClause.append(" OR ");
                likeClause.append("LOWER(p.name) LIKE LOWER(?)");
                gParams.add("%" + t + "%");
            }
            StringBuilder gSql = new StringBuilder(
                "SELECT sc.name AS chain_name, p.id AS product_id, p.name AS product_name, " +
                "p.net_quantity, p.unit, MIN(pr.price) AS min_price " +
                "FROM prices pr " +
                "JOIN stores s ON s.id = pr.store_id " +
                "JOIN store_chains sc ON sc.id = s.chain_id " +
                "JOIN products p ON p.id = pr.product_id " +
                "WHERE pr.is_current = true " +
                "AND (" + likeClause + ")"
            );

            if (city != null && !city.isBlank()) {
                gSql.append(" AND UPPER(s.city) = UPPER(?)");
                gParams.add(city);
            }

            gSql.append(" GROUP BY sc.name, p.id, p.name, p.net_quantity, p.unit");

            // Fetch all matches, then rank in Java by relevance + unit price
            List<RawGenericRow> rows = new ArrayList<>();
            jdbc.query(gSql.toString(), rs -> {
                rows.add(new RawGenericRow(
                    rs.getString("chain_name"),
                    rs.getLong("product_id"),
                    rs.getString("product_name"),
                    rs.getString("net_quantity"),
                    rs.getString("unit"),
                    rs.getBigDecimal("min_price")
                ));
            }, gParams.toArray());

            // Step 1: Find the BEST product globally (across all chains)
            // Score each unique product - strongly prefer products available at MORE chains
            // so the same product is compared across stores
            String termLower = term.toLowerCase();
            Map<Long, Double> productScores = new HashMap<>();
            Map<Long, String> productNameMap = new HashMap<>();
            Map<Long, Set<String>> productChains = new HashMap<>();

            // First pass: collect which chains carry each product
            for (RawGenericRow row : rows) {
                productChains.computeIfAbsent(row.productId, k -> new HashSet<>()).add(row.chainName);
                productNameMap.put(row.productId, row.productName);
            }

            int totalChains = (int) rows.stream().map(r -> r.chainName).distinct().count();

            for (RawGenericRow row : rows) {
                String nameLower = row.productName.toLowerCase();
                // Check relevance against ALL expanded terms (original + translations)
                int relevance = computeRelevance(nameLower, expandedTerms);
                int sizePreference = isStandardSize(row.netQuantity, row.unit, termLower) ? 0 : 1;
                double unitPrice = computeUnitPrice(row.price, row.netQuantity, row.unit);

                // Coverage penalty: products available at fewer chains get a huge penalty
                // so the algorithm strongly prefers the same product across all stores
                int chainCount = productChains.get(row.productId).size();
                int coveragePenalty = totalChains - chainCount; // 0 if available everywhere

                double score = coveragePenalty * 100_000_000.0
                    + relevance * 10_000_000.0
                    + sizePreference * 1_000_000.0
                    + unitPrice;

                Double existing = productScores.get(row.productId);
                if (existing == null || score < existing) {
                    productScores.put(row.productId, score);
                }
            }

            // Sort products by score - best first (lowest score = most chains + best match)
            List<Map.Entry<Long, Double>> rankedProducts = new ArrayList<>(productScores.entrySet());
            rankedProducts.sort(Map.Entry.comparingByValue());

            // Step 2: For each chain, use the SAME best product if available,
            // otherwise fall back to the next best product that IS available
            Map<String, GenericMatch> chainBest = new LinkedHashMap<>();

            // Collect which chains have which products
            Map<String, Map<Long, BigDecimal>> chainProductPricesForGeneric = new HashMap<>();
            for (RawGenericRow row : rows) {
                chainProductPricesForGeneric
                    .computeIfAbsent(row.chainName, k -> new HashMap<>())
                    .put(row.productId, row.price);
            }

            for (Map.Entry<String, Map<Long, BigDecimal>> chainEntry : chainProductPricesForGeneric.entrySet()) {
                String chain = chainEntry.getKey();
                Map<Long, BigDecimal> availableProducts = chainEntry.getValue();

                // Walk through ranked products, pick first available at this chain
                for (Map.Entry<Long, Double> ranked : rankedProducts) {
                    Long pid = ranked.getKey();
                    BigDecimal price = availableProducts.get(pid);
                    if (price != null) {
                        chainBest.put(chain, new GenericMatch(pid, productNameMap.get(pid), price));
                        break;
                    }
                }
            }

            genericMatches.put("generic_" + gi.getId(), chainBest);
        }

        // Build product info for specific items
        Map<Long, String> productNames = new LinkedHashMap<>();
        Map<Long, Integer> productQuantities = new HashMap<>();
        List<Long> productIds = new ArrayList<>();
        for (ShoppingListItem item : specificItems) {
            Long pid = item.getProduct().getId();
            productIds.add(pid);
            productNames.put(pid, item.getProduct().getName());
            productQuantities.put(pid, item.getQuantity());
        }

        // Each chain stores its own product rows, so the same physical item has a
        // different product_id per chain. Expand every selected product to all
        // products sharing its barcode, and map each equivalent id back to the
        // original so prices aggregate under the item the user actually added.
        Map<Long, Long> equivToOrig = new HashMap<>();
        for (Long pid : productIds) equivToOrig.put(pid, pid);
        if (!productIds.isEmpty()) {
            StringJoiner idJoin = new StringJoiner(",");
            for (Long pid : productIds) idJoin.add(pid.toString());
            // Normalize barcodes (strip a float ".0") so 123 and 123.0 — the same
            // product split across rows by feed formatting — are treated as equal.
            Map<String, Long> barcodeToOrig = new HashMap<>();
            jdbc.query("SELECT regexp_replace(barcode, '\\.0+$', '') AS nb, id FROM products WHERE id IN (" + idJoin +
                    ") AND barcode IS NOT NULL AND barcode <> ''",
                (org.springframework.jdbc.core.RowCallbackHandler)
                rs -> barcodeToOrig.put(rs.getString("nb"), rs.getLong("id")));
            if (!barcodeToOrig.isEmpty()) {
                StringJoiner ph = new StringJoiner(",");
                List<Object> bParams = new ArrayList<>();
                for (String bc : barcodeToOrig.keySet()) { ph.add("?"); bParams.add(bc); }
                jdbc.query("SELECT id, regexp_replace(barcode, '\\.0+$', '') AS nb FROM products " +
                        "WHERE regexp_replace(barcode, '\\.0+$', '') IN (" + ph + ")",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        Long origin = barcodeToOrig.get(rs.getString("nb"));
                        if (origin != null) equivToOrig.put(rs.getLong("id"), origin);
                    }, bParams.toArray());
            }
        }

        // Get specific product prices per chain (cheapest equivalent per chain)
        Map<String, Map<Long, BigDecimal>> chainProductPrices = new HashMap<>();
        if (!equivToOrig.isEmpty()) {
            StringBuilder sql = new StringBuilder(
                "SELECT sc.name AS chain_name, pr.product_id, MIN(pr.price) AS min_price " +
                "FROM prices pr " +
                "JOIN stores s ON s.id = pr.store_id " +
                "JOIN store_chains sc ON sc.id = s.chain_id " +
                "WHERE pr.is_current = true " +
                "AND pr.product_id IN ("
            );
            StringJoiner joiner = new StringJoiner(",");
            for (Long pid : equivToOrig.keySet()) joiner.add(pid.toString());
            sql.append(joiner).append(")");

            List<Object> params = new ArrayList<>();
            if (city != null && !city.isBlank()) {
                sql.append(" AND UPPER(s.city) = UPPER(?)");
                params.add(city);
            }
            sql.append(" GROUP BY sc.name, pr.product_id");

            jdbc.query(sql.toString(), rs -> {
                String chainName = rs.getString("chain_name");
                Long productId = rs.getLong("product_id");
                BigDecimal price = rs.getBigDecimal("min_price");
                Long origPid = equivToOrig.getOrDefault(productId, productId);
                chainProductPrices
                    .computeIfAbsent(chainName, k -> new HashMap<>())
                    .merge(origPid, price, (a, b) -> a.compareTo(b) <= 0 ? a : b);
            }, params.toArray());
        }

        // Hybrid "exact, then offer": for every specific product, precompute the
        // cheapest name-equivalent per chain. This both supplies the tappable
        // suggestion AND lets chains that lack the exact item still appear.
        // chain -> (origProductId -> suggested ItemPriceDetail)
        Map<String, Map<Long, ItemPriceDetail>> specSuggestions = new HashMap<>();
        for (ShoppingListItem si : specificItems) {
            Long pid = si.getProduct().getId();
            int qty = productQuantities.getOrDefault(pid, 1);
            // One query returns the cheapest equivalent per chain (was 1 query per chain).
            Map<String, ItemPriceDetail> perChain =
                    cheapestEquivalentPerChain(pid, si.getProduct().getName(), qty, city);
            for (var e : perChain.entrySet()) {
                specSuggestions.computeIfAbsent(e.getKey(), k -> new HashMap<>())
                        .put(pid, e.getValue());
            }
        }

        // Collect all chain names
        Set<String> allChains = new LinkedHashSet<>();
        allChains.addAll(chainProductPrices.keySet());
        allChains.addAll(specSuggestions.keySet());
        for (Map<String, GenericMatch> gm : genericMatches.values()) {
            allChains.addAll(gm.keySet());
        }

        // Build item labels (for display)
        Map<String, String> itemLabels = new LinkedHashMap<>();
        for (ShoppingListItem si : specificItems) {
            itemLabels.put("specific_" + si.getProduct().getId(), si.getProduct().getName());
        }
        for (ShoppingListItem gi : genericItems) {
            itemLabels.put("generic_" + gi.getId(), gi.getSearchTerm());
            itemQuantities.put("generic_" + gi.getId(), gi.getQuantity());
        }

        // Build results per chain
        List<StoreOptimizationResult> results = new ArrayList<>();
        for (String chainName : allChains) {
            List<ItemPriceDetail> chainItems = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            List<ItemPriceDetail> suggestions = new ArrayList<>();

            // Specific products
            Map<Long, BigDecimal> chainPrices = chainProductPrices.getOrDefault(chainName, Map.of());
            for (Map.Entry<Long, String> pEntry : productNames.entrySet()) {
                Long pid = pEntry.getKey();
                BigDecimal price = chainPrices.get(pid);
                int qty = productQuantities.getOrDefault(pid, 1);
                if (price != null) {
                    chainItems.add(new ItemPriceDetail(pid, pEntry.getValue(), null, qty, price,
                        price.multiply(BigDecimal.valueOf(qty))));
                } else {
                    missing.add(pEntry.getValue());
                    // Hybrid: exact item not here, offer the precomputed equivalent.
                    ItemPriceDetail sug = specSuggestions
                            .getOrDefault(chainName, Map.of()).get(pid);
                    if (sug != null) suggestions.add(sug);
                }
            }

            // Generic items
            for (ShoppingListItem gi : genericItems) {
                String key = "generic_" + gi.getId();
                Map<String, GenericMatch> gm = genericMatches.getOrDefault(key, Map.of());
                GenericMatch match = gm.get(chainName);
                int qty = itemQuantities.getOrDefault(key, 1);

                if (match != null) {
                    String displayName = gi.getSearchTerm() + " -> " + match.productName;
                    chainItems.add(new ItemPriceDetail(match.productId, displayName, gi.getSearchTerm(), qty,
                        match.price, match.price.multiply(BigDecimal.valueOf(qty))));
                } else {
                    missing.add(gi.getSearchTerm());
                }
            }

            BigDecimal total = chainItems.stream()
                .map(ItemPriceDetail::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

            boolean complete = missing.isEmpty();
            results.add(new StoreOptimizationResult(null, chainName, city, total, chainItems, complete, missing, suggestions));
        }

        results.sort(Comparator
            .comparing((StoreOptimizationResult r) -> !r.complete())
            .thenComparing(r -> r.complete()
                ? r.totalPrice()
                : BigDecimal.valueOf(r.missingProducts().size()))
        );

        return results;
    }

    /**
     * Returns alternative products at a given chain for a search term, sorted by price.
     * Splits the search term into keywords and matches products containing ANY keyword,
     * then ranks by how many keywords match (more = better), then by price.
     */
    public List<ItemPriceDetail> getAlternatives(String chainName, String searchTerm, Long currentProductId, String city) {
        // Look up subcategory of the current product for filtering
        String subcategory = null;
        if (currentProductId != null) {
            subcategory = productRepo.findById(currentProductId)
                .map(Product::getSubcategory)
                .orElse(null);
        }

        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder(
            "SELECT p.id AS product_id, p.name AS product_name, MIN(pr.price) AS min_price "
        );

        sql.append("FROM prices pr " +
            "JOIN stores s ON s.id = pr.store_id " +
            "JOIN store_chains sc ON sc.id = s.chain_id " +
            "JOIN products p ON p.id = pr.product_id " +
            "WHERE pr.is_current = true " +
            "AND LOWER(sc.name) = LOWER(?) "
        );
        params.add(chainName);

        // Build keyword list from search term, expanded with translations
        String[] words = searchTerm.toLowerCase().split("\\s+");
        Set<String> keywordSet = new LinkedHashSet<>();
        for (String w : words) {
            String clean = w.replaceAll("[^a-zčćžšđ0-9]", "");
            if (clean.length() >= 2) {
                keywordSet.add(clean);
                keywordSet.addAll(translationService.expandSearch(clean));
            }
        }
        if (keywordSet.isEmpty()) {
            keywordSet.add(searchTerm.toLowerCase().trim());
            keywordSet.addAll(translationService.expandSearch(searchTerm.trim()));
        }
        List<String> keywords = new ArrayList<>(keywordSet);

        // If we have a subcategory, use it as the primary filter (fast, indexed)
        // and add a simple LIKE keyword check
        if (subcategory != null && !subcategory.isBlank()) {
            sql.append("AND p.subcategory = ? ");
            params.add(subcategory);
            // Simple keyword match - subcategory already narrows the pool
            sql.append("AND (");
            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("LOWER(p.name) LIKE ?");
                params.add("%" + keywords.get(i) + "%");
            }
            sql.append(") ");
        } else {
            // No subcategory - use LIKE with keyword at start of name for speed
            sql.append("AND (");
            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("LOWER(p.name) LIKE ?");
                params.add(keywords.get(i) + "%");
            }
            sql.append(") ");
        }

        if (city != null && !city.isBlank()) {
            sql.append(" AND UPPER(s.city) = UPPER(?)");
            params.add(city);
        }

        sql.append(" GROUP BY p.id, p.name ORDER BY min_price ASC LIMIT 15");

        List<ItemPriceDetail> alternatives = new ArrayList<>();
        jdbc.query(sql.toString(), rs -> {
            alternatives.add(new ItemPriceDetail(
                rs.getLong("product_id"),
                rs.getString("product_name"),
                searchTerm,
                1,
                rs.getBigDecimal("min_price"),
                rs.getBigDecimal("min_price")
            ));
        }, params.toArray());

        return alternatives;
    }

    /**
     * Cheapest equivalent product per chain for a given product, in ONE query.
     * Used by optimize's "exact, then offer" hybrid - replaces the old per-chain
     * loop that ran ~25 queries (and timed out) with a single window-function query.
     */
    private Map<String, ItemPriceDetail> cheapestEquivalentPerChain(
            Long productId, String name, int qty, String city) {

        String subcategory = productRepo.findById(productId)
                .map(Product::getSubcategory).orElse(null);

        Set<String> keywordSet = new LinkedHashSet<>();
        for (String w : name.toLowerCase().split("\\s+")) {
            String clean = w.replaceAll("[^a-zčćžšđ0-9]", "");
            if (clean.length() >= 2) {
                keywordSet.add(clean);
                keywordSet.addAll(translationService.expandSearch(clean));
            }
        }
        if (keywordSet.isEmpty()) return Map.of();
        List<String> keywords = new ArrayList<>(keywordSet);

        // Step 1: find candidate products by name (bounded, off the products table)
        // so step 2 never scans 9.6M price rows with a LIKE.
        List<Object> cp = new ArrayList<>();
        StringBuilder cs = new StringBuilder("SELECT id FROM products WHERE ");
        if (subcategory != null && !subcategory.isBlank()) {
            cs.append("subcategory = ? AND (");
            cp.add(subcategory);
            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) cs.append(" OR ");
                cs.append("LOWER(name) LIKE ?");
                cp.add("%" + keywords.get(i) + "%");
            }
            cs.append(")");
        } else {
            cs.append("(");
            for (int i = 0; i < keywords.size(); i++) {
                if (i > 0) cs.append(" OR ");
                cs.append("LOWER(name) LIKE ?");
                cp.add(keywords.get(i) + "%");
            }
            cs.append(")");
        }
        cs.append(" LIMIT 400");
        List<Long> candidates = jdbc.queryForList(cs.toString(), cp.toArray(), Long.class);
        if (candidates.isEmpty()) return Map.of();

        // Step 2: cheapest current price per chain among those candidates (indexed by product_id).
        StringJoiner ids = new StringJoiner(",");
        for (Long id : candidates) ids.add(id.toString());
        List<Object> params = new ArrayList<>();
        StringBuilder inner = new StringBuilder(
            "SELECT sc.name AS chain, p.id AS product_id, p.name AS product_name, " +
            "MIN(pr.price) AS price, " +
            "ROW_NUMBER() OVER (PARTITION BY sc.name ORDER BY MIN(pr.price) ASC) AS rn " +
            "FROM prices pr " +
            "JOIN stores s ON s.id = pr.store_id " +
            "JOIN store_chains sc ON sc.id = s.chain_id " +
            "JOIN products p ON p.id = pr.product_id " +
            "WHERE pr.is_current = true AND pr.product_id IN (" + ids + ") ");
        if (city != null && !city.isBlank()) {
            inner.append("AND UPPER(s.city) = UPPER(?) ");
            params.add(city);
        }
        inner.append("GROUP BY sc.name, p.id, p.name");

        String sql = "SELECT chain, product_id, product_name, price FROM (" + inner +
                ") t WHERE rn = 1";

        Map<String, ItemPriceDetail> perChain = new HashMap<>();
        jdbc.query(sql, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            BigDecimal price = rs.getBigDecimal("price");
            perChain.put(rs.getString("chain"), new ItemPriceDetail(
                rs.getLong("product_id"), rs.getString("product_name"), name, qty,
                price, price.multiply(BigDecimal.valueOf(qty))));
        }, params.toArray());
        return perChain;
    }

    private record GenericMatch(Long productId, String productName, BigDecimal price) {}

    private record RawGenericRow(String chainName, Long productId, String productName,
                                 String netQuantity, String unit, BigDecimal price) {}

    /**
     * Checks if a product has a "standard" size for its type.
     * Standard = the most common/expected size a consumer would buy.
     */
    private boolean isStandardSize(String netQuantity, String unit, String searchTerm) {
        if (netQuantity == null || netQuantity.isBlank()) return false;

        double[] parsed = parseQuantityAndUnit(netQuantity, unit);
        double qty = parsed[0];      // normalized quantity in base unit (L or kg)
        int unitType = (int) parsed[1]; // 0 = volume (L), 1 = weight (kg), 2 = pieces, -1 = unknown

        // Define standard sizes per product type [min, max] in base unit
        // Volumes in liters, weights in kg, pieces as count
        if (unitType == 0) { // volume - liters
            if (matches(searchTerm, "mlijeko", "mlijek"))       return qty >= 0.9 && qty <= 1.1;
            if (matches(searchTerm, "sok", "juice"))            return qty >= 0.9 && qty <= 2.1;
            if (matches(searchTerm, "vino", "wine"))            return qty >= 0.7 && qty <= 0.8;
            if (matches(searchTerm, "pivo", "beer"))            return qty >= 0.45 && qty <= 0.55;
            if (matches(searchTerm, "ulje", "oil"))             return qty >= 0.9 && qty <= 1.1;
            if (matches(searchTerm, "ocat", "vinegar"))         return qty >= 0.9 && qty <= 1.1;
            if (matches(searchTerm, "jogurt"))                  return qty >= 0.15 && qty <= 0.5;
            if (matches(searchTerm, "vrhnje"))                  return qty >= 0.15 && qty <= 0.5;
            if (matches(searchTerm, "voda", "water"))           return qty >= 1.0 && qty <= 2.0;
            if (matches(searchTerm, "napitak"))                 return qty >= 0.3 && qty <= 1.5;
            // Default for unknown liquid: prefer 0.5-1L
            return qty >= 0.45 && qty <= 1.1;
        }
        if (unitType == 1) { // weight - kg
            if (matches(searchTerm, "sir", "cheese"))           return qty >= 0.1 && qty <= 0.5;
            if (matches(searchTerm, "brasno", "brašno", "flour")) return qty >= 0.9 && qty <= 1.1;
            if (matches(searchTerm, "riza", "riža", "rice"))    return qty >= 0.9 && qty <= 1.1;
            if (matches(searchTerm, "secer", "šećer", "sugar")) return qty >= 0.9 && qty <= 1.1;
            if (matches(searchTerm, "kruh", "bread"))           return qty >= 0.3 && qty <= 0.8;
            if (matches(searchTerm, "tjestenina", "pasta"))     return qty >= 0.4 && qty <= 0.55;
            if (matches(searchTerm, "maslac", "butter"))        return qty >= 0.2 && qty <= 0.3;
            if (matches(searchTerm, "kava", "coffee"))          return qty >= 0.1 && qty <= 0.3;
            if (matches(searchTerm, "caj", "čaj", "tea"))       return qty >= 0.02 && qty <= 0.06;
            if (matches(searchTerm, "cokolada", "čokolada"))    return qty >= 0.08 && qty <= 0.2;
            if (matches(searchTerm, "keks", "biscuit"))         return qty >= 0.15 && qty <= 0.35;
            if (matches(searchTerm, "cips", "čips", "chips"))   return qty >= 0.1 && qty <= 0.25;
            if (matches(searchTerm, "salama"))                  return qty >= 0.1 && qty <= 0.5;
            if (matches(searchTerm, "kobasica"))                return qty >= 0.2 && qty <= 0.5;
            // Default for unknown weight: prefer 0.2-1kg
            return qty >= 0.2 && qty <= 1.1;
        }
        if (unitType == 2) { // pieces
            if (matches(searchTerm, "jaja", "jaje", "egg"))     return qty >= 9 && qty <= 11;
            // Default pieces: prefer 1-12
            return qty >= 1 && qty <= 12;
        }

        return true; // unknown unit type - don't penalize
    }

    /**
     * Computes relevance score for a product name against a set of search terms.
     * 0 = exact word match (best), 1 = name starts with term,
     * 2 = term appears at word boundary, 3 = substring only (worst).
     * Returns the best (lowest) score across all terms.
     */
    private int computeRelevance(String nameLower, Set<String> terms) {
        int best = 3; // worst = substring match
        for (String t : terms) {
            String tLower = t.toLowerCase();
            // Exact match or standalone word
            if (nameLower.equals(tLower)
                || nameLower.startsWith(tLower + " ")
                || nameLower.endsWith(" " + tLower)
                || nameLower.contains(" " + tLower + " ")) {
                return 0; // can't do better
            }
            // Name starts with term (e.g. "mlijeko" starts with "mlije")
            if (nameLower.startsWith(tLower) && best > 1) {
                best = 1;
            }
            // Term appears after a word boundary (space)
            if (nameLower.contains(" " + tLower) && best > 2) {
                best = 2;
            }
        }
        return best;
    }

    private boolean matches(String text, String... patterns) {
        for (String p : patterns) {
            if (text.contains(p)) return true;
        }
        return false;
    }

    /**
     * Parses net_quantity + unit into [normalizedQty, unitType].
     * unitType: 0=volume(L), 1=weight(kg), 2=pieces, -1=unknown
     */
    private double[] parseQuantityAndUnit(String netQuantity, String unit) {
        if (netQuantity == null || netQuantity.isBlank()) return new double[]{0, -1};

        String raw = netQuantity.trim();
        String unitCol = unit != null ? unit.trim().toLowerCase() : "";

        // Extract embedded unit from net_quantity
        String embeddedUnit = null;
        String numericPart = raw;
        int lastSpace = raw.lastIndexOf(' ');
        if (lastSpace > 0) {
            String possibleUnit = raw.substring(lastSpace + 1).trim();
            String possibleNum = raw.substring(0, lastSpace).trim();
            if (possibleUnit.matches("(?i)[a-z]+") && !possibleNum.isEmpty()) {
                embeddedUnit = possibleUnit.toLowerCase();
                numericPart = possibleNum;
            }
        }

        String realUnit;
        if (embeddedUnit != null) {
            realUnit = embeddedUnit;
        } else if (!unitCol.isEmpty() && !unitCol.equals("ko") && !unitCol.equals("kom")) {
            realUnit = unitCol;
        } else {
            // Pieces
            double count;
            try { count = Double.parseDouble(numericPart.replace(',', '.')); }
            catch (NumberFormatException e) { return new double[]{0, -1}; }
            return new double[]{count, 2};
        }

        double qty;
        try { qty = Double.parseDouble(numericPart.replace(',', '.')); }
        catch (NumberFormatException e) { return new double[]{0, -1}; }

        switch (realUnit) {
            case "ml": return new double[]{qty * 0.001, 0};
            case "cl": return new double[]{qty * 0.01, 0};
            case "dl": return new double[]{qty * 0.1, 0};
            case "l": case "lit": return new double[]{qty, 0};
            case "g": case "gr": return new double[]{qty * 0.001, 1};
            case "dag": case "dkg": return new double[]{qty * 0.01, 1};
            case "kg": return new double[]{qty, 1};
            default: return new double[]{qty, -1};
        }
    }

    /**
     * Parses messy net_quantity + unit fields and computes price per base unit (per L or per kg).
     * Handles formats like: "1,0000" / "L", "0.20 l" / "ko", "1 L" / "KOM", "0,200 KG" / "KOM"
     */
    private double computeUnitPrice(BigDecimal price, String netQuantity, String unit) {
        if (price == null || netQuantity == null || netQuantity.isBlank()) {
            return price != null ? price.doubleValue() : Double.MAX_VALUE;
        }

        String raw = netQuantity.trim();
        String unitCol = unit != null ? unit.trim().toLowerCase() : "";

        // Extract embedded unit from net_quantity if present (e.g. "1 L", "0,200 KG")
        String embeddedUnit = null;
        String numericPart = raw;

        // Match patterns like "0.20 l", "1 L", "0,200 KG"
        int lastSpace = raw.lastIndexOf(' ');
        if (lastSpace > 0) {
            String possibleUnit = raw.substring(lastSpace + 1).trim();
            String possibleNum = raw.substring(0, lastSpace).trim();
            if (possibleUnit.matches("(?i)[a-z]+") && !possibleNum.isEmpty()) {
                embeddedUnit = possibleUnit.toLowerCase();
                numericPart = possibleNum;
            }
        }

        // Determine the real unit: prefer embedded unit, fall back to column
        String realUnit;
        if (embeddedUnit != null) {
            realUnit = embeddedUnit;
        } else if (!unitCol.isEmpty() && !unitCol.equals("ko") && !unitCol.equals("kom")) {
            realUnit = unitCol;
        } else {
            // Unit is "ko"/"kom" (piece/pack) with no embedded unit - treat as pack count
            // e.g. eggs: net_quantity="10", unit="KOM" -> price per piece = price/10
            double packCount;
            try {
                packCount = Double.parseDouble(numericPart.replace(',', '.'));
            } catch (NumberFormatException e) {
                return price.doubleValue();
            }
            if (packCount <= 0) return price.doubleValue();
            return price.doubleValue() / packCount;
        }

        // Parse the numeric value (handle comma decimals)
        double qty;
        try {
            qty = Double.parseDouble(numericPart.replace(',', '.'));
        } catch (NumberFormatException e) {
            return price.doubleValue();
        }
        if (qty <= 0) return price.doubleValue();

        // Normalize to base unit (L or kg)
        double multiplier;
        switch (realUnit) {
            case "ml": multiplier = 0.001; break;
            case "cl": multiplier = 0.01; break;
            case "dl": multiplier = 0.1; break;
            case "l": case "lit": multiplier = 1.0; break;
            case "g": case "gr": multiplier = 0.001; break;
            case "dag": case "dkg": multiplier = 0.01; break;
            case "kg": multiplier = 1.0; break;
            default: multiplier = 1.0;
        }

        double normalizedQty = qty * multiplier;
        if (normalizedQty <= 0) return price.doubleValue();

        return price.doubleValue() / normalizedQty;
    }
}