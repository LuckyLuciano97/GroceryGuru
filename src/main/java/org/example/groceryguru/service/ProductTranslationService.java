package org.example.groceryguru.service;

import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Provides cross-language product search mappings between English and Croatian.
 * When a user searches for "bread", the system also searches for "kruh", etc.
 */
@Service
public class ProductTranslationService {

    private final Map<String, Set<String>> synonyms = new HashMap<>();

    public ProductTranslationService() {
        // Direct 1:1 translations (tight groups - no broad/related terms)
        addGroup("bread", "kruh");
        addGroup("pastry", "pecivo");
        addGroup("bakery", "pekara");
        addGroup("milk", "mlijeko");
        addGroup("eggs", "egg", "jaja", "jaje");
        addGroup("butter", "maslac");
        addGroup("cheese", "sir");
        addGroup("yogurt", "jogurt");
        addGroup("cream", "vrhnje", "smetana");
        addGroup("water", "voda");
        addGroup("juice", "sok");
        addGroup("coffee", "kava");
        addGroup("tea", "čaj");
        addGroup("sugar", "šećer");
        addGroup("salt", "sol");
        addGroup("flour", "brašno");
        addGroup("rice", "riža");
        addGroup("pasta", "tjestenina");
        addGroup("oil", "ulje");
        addGroup("vinegar", "ocat");
        addGroup("meat", "meso");
        addGroup("chicken", "piletina", "pilet");
        addGroup("pork", "svinjetina", "svinj");
        addGroup("beef", "govedina", "junetina");
        addGroup("fish", "riba");
        addGroup("sausage", "kobasica");
        addGroup("hot dog", "hrenovka");
        addGroup("ham", "šunka");
        addGroup("bacon", "slanina");
        addGroup("apple", "jabuka");
        addGroup("banana", "banana");
        addGroup("orange", "naranča");
        addGroup("lemon", "limun");
        addGroup("tomato", "rajčica", "paradajz");
        addGroup("potato", "krumpir");
        addGroup("onion", "luk");
        addGroup("garlic", "češnjak");
        addGroup("pepper", "paprika");
        addGroup("black pepper", "papar");
        addGroup("carrot", "mrkva");
        addGroup("cucumber", "krastavac");
        addGroup("lettuce", "salata");
        addGroup("cabbage", "kupus", "zelje");
        addGroup("chocolate", "čokolada");
        addGroup("cookie", "keks");
        addGroup("cake", "kolač", "torta");
        addGroup("ice cream", "sladoled");
        addGroup("beer", "pivo");
        addGroup("wine", "vino");
        addGroup("detergent", "deterdžent");
        addGroup("soap", "sapun");
        addGroup("shampoo", "šampon");
        addGroup("toilet paper", "toaletni papir", "wc papir");
        addGroup("tissue", "maramica", "maramice");
        addGroup("diaper", "pelena", "pelene");
        addGroup("cereal", "žitarice", "pahuljice");
        addGroup("honey", "med");
        addGroup("jam", "džem", "marmelada", "pekmez");
        addGroup("ketchup", "kečap");
        addGroup("mayonnaise", "majoneza");
        addGroup("mustard", "senf");
        addGroup("canned", "konzerva", "konzerve");
        addGroup("frozen", "smrznuto", "zamrznuto");
        addGroup("chips", "čips");
        addGroup("crackers", "krekeri");
        addGroup("nuts", "orasi", "orašasti");
        addGroup("peanuts", "kikiriki");
        addGroup("tuna", "tunjevina");
        addGroup("sardine", "sardina");
        addGroup("corn", "kukuruz");
        addGroup("beans", "grah");
        addGroup("peas", "grašak");
        addGroup("mushroom", "gljive", "šampinjoni");
        addGroup("spinach", "špinat");
        addGroup("broccoli", "brokula");
        addGroup("zucchini", "tikvica");
        addGroup("eggplant", "patlidžan");
        addGroup("watermelon", "lubenica");
        addGroup("grape", "grožđe");
        addGroup("strawberry", "jagoda", "jagode");
        addGroup("peach", "breskva");
        addGroup("pear", "kruška");
        addGroup("plum", "šljiva");
        addGroup("cherry", "trešnja", "višnja");

        // Croatian grocery abbreviations -> full word, so typing the truncated
        // form a shopper sees on a receipt still finds the product.
        addGroup("mlij", "mlijeko");
        addGroup("jog", "jogurt");
        addGroup("čok", "čokolada");
        addGroup("cok", "čokolada");
        addGroup("nar", "naranča");
        addGroup("jab", "jabuka");
        addGroup("pile", "piletina", "pileća");
        addGroup("svinj", "svinjetina", "svinjsko");
        addGroup("gov", "govedina", "goveđe");
        addGroup("deterdz", "deterdžent");
    }

    private void addGroup(String... words) {
        Set<String> group = new LinkedHashSet<>(Arrays.asList(words));
        for (String word : words) {
            synonyms.computeIfAbsent(word.toLowerCase(), k -> new LinkedHashSet<>()).addAll(group);
        }
    }

    /**
     * Expands a search term with cross-language synonyms.
     * Returns the original term plus any translations.
     */
    public Set<String> expandSearch(String searchTerm) {
        Set<String> result = new LinkedHashSet<>();
        result.add(searchTerm);

        String lower = searchTerm.toLowerCase().trim();
        Set<String> group = synonyms.get(lower);
        if (group != null) {
            result.addAll(group);
        }

        return result;
    }
}
