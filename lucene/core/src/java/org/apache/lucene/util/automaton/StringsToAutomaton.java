/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.lucene.util.automaton;

import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import org.apache.lucene.util.ArrayUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CharsRef;
import org.apache.lucene.util.CharsRefBuilder;

/**
 * Builds a minimal, deterministic {@link Automaton} that accepts a set of strings using the
 * algorithm described in <a href="https://aclanthology.org/J00-1002.pdf">Incremental Construction
 * of Minimal Acyclic Finite-State Automata by Daciuk, Mihov, Watson and Watson</a>. This requires
 * sorted input data, but is very fast (nearly linear with the input size).
 *
 * @see Automata#makeStringUnion(Collection)
 */
final class StringsToAutomaton {

  /** The default constructor is private. Use static methods directly. */
  private StringsToAutomaton() {
    super();
  }

  /** DFSA state with <code>char</code> labels on transitions. */
  private static final class State {

    /** An empty set of labels. */
    private static final int[] NO_LABELS = new int[0];

    /** An empty set of states. */
    private static final State[] NO_STATES = new State[0];

    /**
     * Labels of outgoing transitions. Indexed identically to {@link #states}. Labels must be sorted
     * lexicographically.
     */
    int[] labels = NO_LABELS;

    /** States reachable from outgoing transitions. Indexed identically to {@link #labels}. */
    State[] states = NO_STATES;

    /** <code>true</code> if this state corresponds to the end of at least one input sequence. */
    boolean is_final;

    /**
     * Returns the target state of a transition leaving this state and labeled with <code>label
     * </code>. If no such transition exists, returns <code>null</code>.
     */
    State getState(int label) {
      final int index = Arrays.binarySearch(labels, label);
      return index >= 0 ? states[index] : null;
    }

    /**
     * Two states are equal if:
     *
     * <ul>
     *   <li>they have an identical number of outgoing transitions, labeled with the same labels
     *   <li>corresponding outgoing transitions lead to the same states (to states with an identical
     *       right-language).
     * </ul>
     */
    @Override
    public boolean equals(Object obj) {
      final State other = (State) obj;
      return is_final == other.is_final
          && Arrays.equals(this.labels, other.labels)
          && referenceEquals(this.states, other.states);
    }

    /** Compute the hash code of the <i>current</i> status of this state. */
    @Override
    public int hashCode() {
      int hash = is_final ? 1 : 0;

      hash ^= hash * 31 + this.labels.length;
      for (int c : this.labels) hash ^= hash * 31 + c;

      /*
       * Compare the right-language of this state using reference-identity of
       * outgoing states. This is possible because states are interned (stored
       * in registry) and traversed in post-order, so any outgoing transitions
       * are already interned.
       */
      for (State s : this.states) {
        hash ^= System.identityHashCode(s);
      }

      return hash;
    }

    /** Return <code>true</code> if this state has any children (outgoing transitions). */
    boolean hasChildren() {
      return labels.length > 0;
    }

    /**
     * Create a new outgoing transition labeled <code>label</code> and return the newly created
     * target state for this transition.
     */
    State newState(int label) {
      assert Arrays.binarySearch(labels, label) < 0
          : "State already has transition labeled: " + label;

      labels = ArrayUtil.growExact(labels, labels.length + 1);
      states = ArrayUtil.growExact(states, states.length + 1);

      labels[labels.length - 1] = label;
      return states[states.length - 1] = new State();
    }

    /** Return the most recent transitions's target state. */
    State lastChild() {
      assert hasChildren() : "No outgoing transitions.";
      return states[states.length - 1];
    }

    /**
     * Return the associated state if the most recent transition is labeled with <code>label</code>.
     */
    State lastChild(int label) {
      final int index = labels.length - 1;
      State s = null;
      if (index >= 0 && labels[index] == label) {
        s = states[index];
      }
      assert s == getState(label);
      return s;
    }

    /** Replace the last added outgoing transition's target state with the given state. */
    void replaceLastChild(State state) {
      assert hasChildren() : "No outgoing transitions.";
      states[states.length - 1] = state;
    }

    /** Compare two lists of objects for reference-equality. */
    private static boolean referenceEquals(Object[] a1, Object[] a2) {
      if (a1.length != a2.length) {
        return false;
      }

      for (int i = 0; i < a1.length; i++) {
        if (a1[i] != a2[i]) {
          return false;
        }
      }

      return true;
    }
  }

  /** A "registry" for state interning. */
  private HashMap<State, State> stateRegistry = new HashMap<>();

  /** Root automaton state. */
  private final State root = new State();

  /** Previous sequence added to the automaton in {@link #add(CharsRef)}. */
  private CharsRefBuilder previous;

  /** A comparator used for enforcing sorted UTF8 order, used in assertions only. */
  @SuppressWarnings("deprecation")
  private static final Comparator<CharsRef> comparator = CharsRef.getUTF16SortedAsUTF8Comparator();

  /**
   * Add another character sequence to this automaton. The sequence must be lexicographically larger
   * or equal compared to any previous sequences added to this automaton (the input must be sorted).
   */
  private void add(CharsRef current) {
    if (current.length > Automata.MAX_STRING_UNION_TERM_LENGTH) {
      throw new IllegalArgumentException(
          "This builder doesn't allow terms that are larger than 1,000 characters, got " + current);
    }
    assert stateRegistry != null : "Automaton already built.";
    assert previous == null || comparator.compare(previous.get(), current) <= 0
        : "Input must be in sorted UTF-8 order: " + previous + " >= " + current;
    assert setPrevious(current);

    // Descend in the automaton (find matching prefix).
    int pos = 0, max = current.length();
    State state = root;
    for (; ; ) {
      assert pos <= max;
      if (pos == max) {
        break;
      }

      int codePoint = Character.codePointAt(current, pos);
      State next = state.lastChild(codePoint);
      if (next == null) {
        break;
      }

      state = next;
      pos += Character.charCount(codePoint);
    }

    if (state.hasChildren()) replaceOrRegister(state);

    addSuffix(state, current, pos);
  }

  /**
   * Finalize the automaton and return the root state. No more strings can be added to the builder
   * after this call.
   *
   * @return Root automaton state.
   */
  private State complete() {
    if (this.stateRegistry == null) throw new IllegalStateException();

    if (root.hasChildren()) replaceOrRegister(root);

    stateRegistry = null;
    return root;
  }

  /** Internal recursive traversal for conversion. */
  private static int convert(
      Automaton.Builder a, State s, IdentityHashMap<State, Integer> visited) {

    Integer converted = visited.get(s);
    if (converted != null) {
      return converted;
    }

    converted = a.createState();
    a.setAccept(converted, s.is_final);

    visited.put(s, converted);
    int i = 0;
    int[] labels = s.labels;
    for (StringsToAutomaton.State target : s.states) {
      a.addTransition(converted, convert(a, target, visited), labels[i++]);
    }

    return converted;
  }

  /**
   * Build a minimal, deterministic automaton from a sorted list of {@link BytesRef} representing
   * strings in UTF-8. These strings must be binary-sorted.
   */
  static Automaton build(Collection<BytesRef> input) {
    final StringsToAutomaton builder = new StringsToAutomaton();

    CharsRefBuilder current = new CharsRefBuilder();
    for (BytesRef b : input) {
      current.copyUTF8Bytes(b);
      builder.add(current.get());
    }

    Automaton.Builder a = new Automaton.Builder();
    convert(a, builder.complete(), new IdentityHashMap<>());

    return a.finish();
  }

  /** Copy <code>current</code> into an internal buffer. */
  private boolean setPrevious(CharsRef current) {
    if (previous == null) {
      previous = new CharsRefBuilder();
    }
    previous.copyChars(current);
    return true;
  }

  /**
   * Replace last child of <code>state</code> with an already registered state or stateRegistry the
   * last child state.
   */
  private void replaceOrRegister(State state) {
    final State child = state.lastChild();

    if (child.hasChildren()) replaceOrRegister(child);

    final State registered = stateRegistry.get(child);
    if (registered != null) {
      state.replaceLastChild(registered);
    } else {
      stateRegistry.put(child, child);
    }
  }

  /**
   * Add a suffix of <code>current</code> starting at <code>fromIndex</code> (inclusive) to state
   * <code>state</code>.
   */
  private void addSuffix(State state, CharSequence current, int fromIndex) {
    final int len = current.length();
    while (fromIndex < len) {
      int cp = Character.codePointAt(current, fromIndex);
      state = state.newState(cp);
      fromIndex += Character.charCount(cp);
    }
    state.is_final = true;
  }
}
