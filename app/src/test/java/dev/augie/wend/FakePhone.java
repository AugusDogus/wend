package dev.augie.wend;

import java.util.ArrayList;
import java.util.List;

/** The head unit's Bluetooth service for tests: a phone in {@link #state}, and what was asked of it. */
final class FakePhone implements HeadUnitPhone.Source {
    Integer state = HeadUnitPhone.IDLE;
    String number = "";
    long address;
    final List<String> book = new ArrayList<>(), calls = new ArrayList<>(), asked = new ArrayList<>();

    @Override public Integer state() { return state; }
    @Override public String number() { return number; }
    @Override public String phoneName() { return "Pixel 8 Pro"; }
    @Override public long phoneAddress() { return address; }
    @Override public List<String> phoneBook() { return book; }
    @Override public List<String> calls() { return calls; }
    @Override public void dial(String dialed) { asked.add("dial " + dialed); }
    @Override public void answer() { asked.add("answer"); }
    @Override public void hangUp() { asked.add("hang up"); }
}
