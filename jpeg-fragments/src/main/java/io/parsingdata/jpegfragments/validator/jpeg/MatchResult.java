package io.parsingdata.jpegfragments.validator.jpeg;

public class MatchResult {

    public final int bitsMatched;
    public final int symbol;

    public MatchResult(final int bitsMatched, final int symbol) {
        this.bitsMatched = bitsMatched;
        this.symbol = symbol;
    }
}
