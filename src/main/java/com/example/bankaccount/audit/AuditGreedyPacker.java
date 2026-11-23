package com.example.bankaccount.audit;

import com.example.bankaccount.domain.AuditPlacement;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.TreeMap;

final class AuditGreedyPacker {
  private AuditGreedyPacker() {}

  static AuditPlacement bestFit(int[] sizes) {
    var binsBySpace = new TreeMap<Integer, ArrayDeque<Integer>>();
    int[] bin = new int[sizes.length];
    int count = 0;
    for (int i = 0; i < sizes.length; i++) {
      var fit = binsBySpace.ceilingEntry(sizes[i]);
      int space = AuditPacker.CAP;
      if (fit == null) {
        bin[i] = count++;
      } else {
        space = fit.getKey();
        bin[i] = fit.getValue().removeFirst();
        if (fit.getValue().isEmpty()) {
          binsBySpace.remove(space);
        }
      }
      int remaining = space - sizes[i];
      if (remaining > 0) {
        binsBySpace.computeIfAbsent(remaining, unused -> new ArrayDeque<>()).addLast(bin[i]);
      }
    }
    return new AuditPlacement(bin, count);
  }

  static AuditPlacement firstFit(int[] sizes) {
    int leaves = Integer.highestOneBit(sizes.length);
    if (leaves < sizes.length) {
      leaves *= 2;
    }
    int[] max = new int[2 * leaves];
    Arrays.fill(max, AuditPacker.CAP);
    int[] bin = new int[sizes.length];
    int count = 0;
    for (int i = 0; i < sizes.length; i++) {
      int node = 1;
      while (node < leaves) {
        node *= 2;
        if (max[node] < sizes[i]) {
          node++;
        }
      }
      bin[i] = node - leaves;
      count = Math.max(count, bin[i] + 1);
      max[node] -= sizes[i];
      while (node > 1) {
        node /= 2;
        max[node] = Math.max(max[2 * node], max[2 * node + 1]);
      }
    }
    return new AuditPlacement(bin, count);
  }
}
