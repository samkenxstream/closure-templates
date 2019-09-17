/*
 * Copyright 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.template.soy.types;

import com.google.auto.value.AutoValue;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Streams;
import com.google.template.soy.soytree.SoyTypeP;
import java.util.Comparator;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Dict type - classic dictionary type with string keys. Only works with field (dot) access.
 *
 * <p>Important: Do not use outside of Soy code (treat as superpackage-private).
 *
 */
public final class RecordType extends SoyType {

  /** The {name, type} pair that is a record member. */
  @AutoValue
  public abstract static class Member {
    public abstract String name();

    public abstract SoyType type();
  }

  public static Member memberOf(String name, SoyType type) {
    return new AutoValue_RecordType_Member(name, type);
  }

  public static final RecordType EMPTY_RECORD = RecordType.of(ImmutableMap.of());

  private final ImmutableList<Member> members;
  // TODO(user): Convert all remaining alphabetized iterators into parse order.
  private final ImmutableSortedMap<String, SoyType> alphabetizedMembers;

  private RecordType(Iterable<Member> members) {
    this.members = ImmutableList.copyOf(members);
    this.alphabetizedMembers =
        Streams.stream(members)
            .collect(
                ImmutableSortedMap.toImmutableSortedMap(
                    Comparator.naturalOrder(), Member::name, Member::type));
  }

  /**
   * This method is problematic in that it doesn't indicate to callers that the iterator order of
   * the members map matters. Prefer {@link #of(Iterable)}.
   */
  @VisibleForTesting
  public static RecordType of(ImmutableMap<String, ? extends SoyType> members) {
    Preconditions.checkArgument(!(members instanceof NavigableMap));
    return new RecordType(
        members.entrySet().stream()
            .map(e -> memberOf(e.getKey(), e.getValue()))
            .collect(Collectors.toList()));
  }

  public static RecordType of(Iterable<Member> members) {
    return new RecordType(members);
  }

  @Override
  public Kind getKind() {
    return Kind.RECORD;
  }

  @Override
  boolean doIsAssignableFromNonUnionType(SoyType srcType) {
    if (srcType.getKind() == Kind.RECORD) {
      RecordType srcRecord = (RecordType) srcType;
      // The source record must have at least all of the members in the dest
      // record.
      for (Map.Entry<String, SoyType> entry : alphabetizedMembers.entrySet()) {
        SoyType fieldType = srcRecord.alphabetizedMembers.get(entry.getKey());
        if (fieldType == null || !entry.getValue().isAssignableFrom(fieldType)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  public boolean isEmpty() {
    return members.isEmpty();
  }

  public ImmutableList<Member> getMembers() {
    return members;
  }

  /** Return the members of this record type. */
  public ImmutableSortedMap<String, SoyType> getAlphabetizedMembers() {
    return alphabetizedMembers;
  }

  public SoyType getMemberType(String fieldName) {
    return alphabetizedMembers.get(fieldName);
  }

  public ImmutableSet<String> getAlphabetizedMemberNames() {
    return alphabetizedMembers.keySet();
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    sb.append("[");
    boolean first = true;
    for (Map.Entry<String, SoyType> entry : alphabetizedMembers.entrySet()) {
      if (first) {
        first = false;
      } else {
        sb.append(", ");
      }
      sb.append(entry.getKey());
      sb.append(": ");
      sb.append(entry.getValue());
    }
    sb.append("]");
    return sb.toString();
  }

  @Override
  void doToProto(SoyTypeP.Builder builder) {
    SoyTypeP.RecordTypeP.Builder recordBuilder = builder.getRecordBuilder();
    for (Member member : members) {
      recordBuilder.putField(member.name(), member.type().toProto());
    }
  }

  @Override
  public boolean equals(Object other) {
    return other != null
        && other.getClass() == this.getClass()
        && ((RecordType) other).members.equals(members);
  }

  @Override
  public int hashCode() {
    return Objects.hash(this.getClass(), members);
  }
}
