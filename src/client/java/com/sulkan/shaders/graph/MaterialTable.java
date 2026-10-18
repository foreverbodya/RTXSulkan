package com.sulkan.shaders.graph;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

public final class MaterialTable {
   private final Map<BlockState, Integer> ids;
   boolean vegetationWind;

   private MaterialTable(Map<BlockState, Integer> ids) {
      this.ids = Collections.unmodifiableMap(ids);
   }

   public boolean enabled() {
      return !this.ids.isEmpty();
   }

   public int id(BlockState state) {
      return this.ids.getOrDefault(state, 0);
   }

   /** Whether accepted terrain vertices remain valid after a renderer change. */
   public boolean sameVertexEncoding(MaterialTable other) {
      if (other == null) return !enabled();
      if (enabled() != other.enabled()) return false;
      if (!enabled()) return true;
      if (vegetationWind != other.vegetationWind || ids.size() != other.ids.size()) return false;
      // IdentityHashMap equality also compares value identity. Material IDs need
      // numeric equality, including IDs outside Integer's small-value cache.
      for (var entry : ids.entrySet()) {
         if (!Objects.equals(entry.getValue(), other.ids.get(entry.getKey()))) return false;
      }
      return true;
   }

   static MaterialTable compile(Map<String, Integer> definitions) {
      Map<BlockState, Integer> ids = new IdentityHashMap<>();
      Map<BlockState, Integer> specificity = new IdentityHashMap<>();
      definitions.forEach(
         (selector, id) -> {
            int bracket = selector.indexOf(91);
            Identifier key = Identifier.parse(bracket < 0 ? selector : selector.substring(0, bracket));
            if (!BuiltInRegistries.BLOCK.containsKey(key)) {
               throw new IllegalArgumentException("Unknown material block " + key);
            }

            Block block = (Block)BuiltInRegistries.BLOCK.getValue(key);
            Map<Property<?>, Comparable<?>> conditions = new LinkedHashMap<>();
            if (bracket >= 0) {
               for (String term : selector.substring(bracket + 1, selector.length() - 1).split(",")) {
                  String[] pair = term.split("=", -1);
                  if (pair.length != 2) {
                     throw new IllegalArgumentException("Invalid material state selector " + selector);
                  }

                  Property<? extends Comparable<?>> property = block.getStateDefinition().getProperty(pair[0]);
                  if (property == null) {
                     throw new IllegalArgumentException("Unknown material property " + term);
                  }

                  Comparable<?> value = (Comparable<?>)property.getValue(pair[1])
                     .orElseThrow(() -> new IllegalArgumentException("Unknown material property value " + term));
                  if (conditions.put(property, value) != null) {
                     throw new IllegalArgumentException("Duplicate material property " + term);
                  }
               }
            }

            Iterator i$ = block.getStateDefinition().getPossibleStates().iterator();

            while (i$.hasNext()) {
               BlockState state = (BlockState)i$.next();
               if (!conditions.entrySet().stream().anyMatch(e -> !state.getValue(e.getKey()).equals(e.getValue()))) {
                  int rank = conditions.size();
                  int old = specificity.getOrDefault(state, -1);
                  if (rank == old && !Objects.equals(ids.get(state), id)) {
                     throw new IllegalArgumentException("Ambiguous material mapping for " + state);
                  }

                  if (rank >= old) {
                     ids.put(state, id);
                     specificity.put(state, rank);
                  }
               }
            }
         }
      );
      return new MaterialTable(ids);
   }
}
