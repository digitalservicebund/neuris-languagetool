/* LanguageTool, a natural language style checker
 * Copyright (C) 2024 Jaume Ortolà
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301
 * USA
 */
package org.languagetool.rules.ca;

import org.jetbrains.annotations.Nullable;
import org.languagetool.AnalyzedToken;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.chunking.ChunkTag;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.patterns.RuleFilter;
import org.languagetool.synthesis.Synthesizer;
import org.languagetool.tagging.Tagger;
import org.languagetool.tools.StringTools;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.languagetool.rules.ca.ApostophationHelper.getPrepositionAndDeterminer;

public class ConvertToGenderAndNumberFilter extends RuleFilter {

  private Pattern splitGenderNumber = Pattern.compile("(N.|A..|V.P..|D..|PX.)(.)(.)(.*)");
  private Pattern splitGenderNumberNoNoun = Pattern.compile("(A..|V.P..|D..|PX.)(.)(.)(.*)");
  private Pattern splitGenderNumberAdjective = Pattern.compile("(A..|V.P..|PX.)(.)(.)(.*)");

  @Nullable
  @Override
  public RuleMatch acceptRuleMatch(RuleMatch match, Map<String, String> arguments, int patternTokenPos,
                                   AnalyzedTokenReadings[] patternTokens, List<Integer> tokenPositions) throws IOException {
    AnalyzedTokenReadings[] tokens = match.getSentence().getTokensWithoutWhitespace();
    Synthesizer synth = getSynthesizerFromRuleMatch(match);
    Tagger tagger = getTaggerFromRuleMatch(match);
    int posWord = 0;
    while (posWord < tokens.length
      && (tokens[posWord].getStartPos() < match.getFromPos() || tokens[posWord].isSentenceStart())) {
      posWord++;
    }
    final String desiredGenderOrigStr = getOptional("gender", arguments, "");
    final String desiredNumberOrigStr = getOptional("number", arguments, "");
    final String lemmaSelect = getRequired("lemmaSelect", arguments);
    final String newLemma = getOptional("newLemma", arguments, "");
    final boolean keepOriginal = getOptional("keepOriginal", arguments, "false").equalsIgnoreCase("true");

    List<String> suggestions = new ArrayList<>();
    List<AnalyzedToken> atrNounList = new ArrayList<>();
    AnalyzedToken atrNounOrig = tokens[posWord].readingWithTagRegex(lemmaSelect);
    GenderAndNumberSplit splitNounOrigPostag = splitGenderAndNumber(atrNounOrig);
    if (!newLemma.isEmpty()) {
      AnalyzedToken at = new AnalyzedToken(atrNounOrig.getToken(), atrNounOrig.getPOSTag(), newLemma);
      atrNounList.add(at);
    } else if (match.getSuggestedReplacements().size() > 0) {
      for (String suggestion : match.getSuggestedReplacements()) {
        List<AnalyzedTokenReadings> atrs = tagger.tag(Arrays.asList(suggestion));
        AnalyzedToken at = atrs.get(0).readingWithTagRegex(splitGenderNumber);
        GenderAndNumberSplit splitPostag = splitGenderAndNumber(at);
        StringBuilder newPostag = new StringBuilder(splitPostag.prefix);
        if (splitPostag.prefix.startsWith("V")) {
          newPostag.append(splitNounOrigPostag.number);
          newPostag.append(splitPostag.gender);
        } else {
          newPostag.append(splitPostag.gender);
          newPostag.append(splitNounOrigPostag.number);
        }
        newPostag.append(splitPostag.suffix);
        AnalyzedToken at2 = new AnalyzedToken(at.getLemma(), newPostag.toString(), at.getLemma());
        atrNounList.add(at2);
      }
    } else {
      atrNounList.add(atrNounOrig);
    }
    int startPos = posWord;
    int endPos = posWord;
    for (AnalyzedToken atrNoun : atrNounList) {
      startPos = posWord;
      endPos = posWord;
      GenderAndNumberSplit splitPostag = splitGenderAndNumber(atrNoun);
      String desiredGenderStr = (!desiredGenderOrigStr.isEmpty() ? desiredGenderOrigStr : splitPostag.gender);
      String desiredNumberStr = (!desiredNumberOrigStr.isEmpty() ? desiredNumberOrigStr : splitPostag.number);
      for (char genderCh : desiredGenderStr.toCharArray()) {
        for (char numberCh : desiredNumberStr.toCharArray()) {
          String desiredGender = String.valueOf(genderCh);
          String desiredNumber = String.valueOf(numberCh);
          StringBuilder suggestionBuilder = new StringBuilder();
          boolean ignoreThisSuggestion = false;
          if (!keepOriginal) {
            String s = synthesizeWithGenderAndNumber(atrNoun, splitPostag, desiredGender, desiredNumber, synth);
            if (s.isEmpty()) {
              ignoreThisSuggestion = true;
            }
            suggestionBuilder.append(s);
          } else {
            suggestionBuilder.append(atrNoun.getToken());
          }
          //backwards
          boolean stop = false;
          int i = posWord;
          String prepositionToAdd = "";
          boolean addDeterminer = false;
          boolean addedDemostrative = false;
          StringBuilder conditionalAddedString = new StringBuilder();
          String addTot = "";
          while (!stop && i > 1) {
            i--;
            AnalyzedToken atr = tokens[i].readingWithTagRegex(splitGenderNumberNoNoun); //getReadingWithPriority
            // (tokens[i]);
            if (tokens[i].hasPosTag("_perfet") || tokens[i].hasPosTag("_GV_") || tokens[i].getChunkTags().contains(new ChunkTag("GV"))) {
              atr = null;
            }
            if (atr != null) {
              if (atr.getPOSTag().startsWith("DA")) {
                suggestionBuilder.insert(0, conditionalAddedString);
                conditionalAddedString.setLength(0);
                addDeterminer = true;
                startPos = i;
              } else {
                if (!addDeterminer && !addedDemostrative) {
                  String s = synthesizeWithGenderAndNumber(atr, splitGenderAndNumber(atr), desiredGender,
                    desiredNumber, synth);
                  if (s.isEmpty()) {
                    ignoreThisSuggestion = true;
                  }
                  if (s.equals("bo")) {
                    s = "bon";
                  }
                  suggestionBuilder.insert(0, conditionalAddedString);
                  conditionalAddedString.setLength(0);
                  if (tokens[i + 1].isWhitespaceBefore()) {
                    suggestionBuilder.insert(0, " ");
                  }
                  suggestionBuilder.insert(0, s);
                  startPos = i;
                  if (atr.getPOSTag().startsWith("DD")) {
                    addedDemostrative = true;
                  }
                  if (atr.getPOSTag().startsWith("D") && !atr.getPOSTag().startsWith("DN") && !addedDemostrative) {
                    stop = true;
                  }
                } else {
                  // only before "el/aquest/aquell...": tota l'estona, tota aquella estona
                  if (atr.getLemma().equals("tot")) {
                    String s = synthesizeWithGenderAndNumber(atr, splitGenderAndNumber(atr), desiredGender,
                      desiredNumber, synth);
                    if (!s.isEmpty()) {
                      addTot = s + " ";
                      startPos = i;
                    }
                  }
                  stop = true;
                }
              }
            } else if (tokens[i].hasPosTag("SPS00") || tokens[i].hasPosTag("LOC_PREP")) {
              if (addDeterminer) {
                String preposition = tokens[i].getToken().toLowerCase();
                if (preposition.equals("pe")) {
                  preposition = "per";
                }
                if (preposition.equals("d'")) {
                  preposition = "de";
                }
                if (preposition.equals("a") || preposition.equals("de") || preposition.equals("per")) {
                  prepositionToAdd = preposition;
                  startPos = i;
                }
              }
              stop = true;
            } else if (tokens[i].hasPosTag("_PUNCT_CONT") || tokens[i].hasPosTag("CC")) {
              if (posWord - i == 1) {
                stop = true;
              } else {
                conditionalAddedString.insert(0, tokens[i].getToken() + " ");
              }
            } else if (tokens[i].hasPosTagStartingWith("RG")
              // adverbi envoltat d'elements que tenen gènere i nombre
              && (i <= 1 || tokens[i - 1].readingWithTagRegex(splitGenderNumber) != null)
              && (i >= tokens.length - 1 || tokens[i + 1].readingWithTagRegex(splitGenderNumber) != null)) {
              conditionalAddedString.insert(0, tokens[i].getToken() + " ");
            } else {
              stop = true;
            }
          }
          // forwards
          stop = false;
          i = posWord;
          conditionalAddedString.setLength(0);
          boolean isThereConjunction = false;
          while (!stop && i < tokens.length - 1) {
            i++;
            AnalyzedToken atr = tokens[i].readingWithTagRegex(splitGenderNumberAdjective);
            if (isThereConjunction && tokens[i].hasPosTagStartingWith("NC")) {
              atr = null;
            }
            if (atr != null) {
              String s = synthesizeWithGenderAndNumber(atr, splitGenderAndNumber(atr), desiredGender, desiredNumber,
                synth);
              if (s.isEmpty()) {
                ignoreThisSuggestion = true;
              }
              suggestionBuilder.append(conditionalAddedString);
              conditionalAddedString.setLength(0);
              suggestionBuilder.append(" " + s);
              endPos = i;
            } else if (tokens[i].hasPosTagStartingWith("RG")) {
              conditionalAddedString.append(" " + tokens[i].getToken());
            } else if (tokens[i].hasPosTag("CC")) {
              isThereConjunction = true;
              conditionalAddedString.append(" " + tokens[i].getToken());
            } else if (tokens[i].hasPosTag("_PUNCT_CONT")) {
              conditionalAddedString.append(tokens[i].getToken());
            } else {
              stop = true;
            }
          }
          if (addDeterminer) {
            suggestionBuilder.insert(0, getPrepositionAndDeterminer(suggestionBuilder.toString(),
              desiredGender + desiredNumber, prepositionToAdd));
          } else if (!prepositionToAdd.isEmpty()) {
            suggestionBuilder.insert(0, prepositionToAdd + " ");
          }
          suggestionBuilder.insert(0, addTot);
          String suggestion = StringTools.preserveCase(suggestionBuilder.toString(), tokens[startPos].getToken());
          if (endPos == posWord && startPos == posWord && tokens[posWord].getToken().equals(suggestion)) {
            continue;
          }
          //TODO: una d'aquests vessants; una dels vessants
          if (!ignoreThisSuggestion) {
            suggestions.add(suggestion);
          }
        }
      }
    }

    if (suggestions.isEmpty()) {
      return null;
    }
    RuleMatch ruleMatch = new RuleMatch(match.getRule(), match.getSentence(), tokens[startPos].getStartPos(),
      tokens[endPos].getEndPos(), match.getMessage(), match.getShortMessage());
    String originalStr = match.getSentence().getText().substring(tokens[startPos].getStartPos(),
      tokens[endPos].getEndPos());
    if (suggestions.contains(originalStr)) {
      return null;
    }
    ruleMatch.setType(match.getType());
    ruleMatch.setSuggestedReplacements(suggestions);
    return ruleMatch;
  }

  class GenderAndNumberSplit {
    String prefix;
    String suffix;
    String gender;
    String number;
  }

  private GenderAndNumberSplit splitGenderAndNumber(AnalyzedToken atr) {
    GenderAndNumberSplit results = new GenderAndNumberSplit();
    Matcher matcherSplit = splitGenderNumber.matcher(atr.getPOSTag());
    if (matcherSplit.matches()) {
      results.prefix = matcherSplit.group(1);
      results.suffix = matcherSplit.group(4);
      String g2 = matcherSplit.group(2);
      String g3 = matcherSplit.group(3);
      if (results.prefix.startsWith("V")) {
        results.gender = g3;
        results.number = g2;
      } else {
        results.gender = g2;
        results.number = g3;
      }
      return results;
    }
    return null;
  }

  private String synthesizeWithGenderAndNumber(AnalyzedToken atr, GenderAndNumberSplit splitPostag, String gender,
                                               String number,
                                               Synthesizer synth) throws IOException {
    if (splitPostag.prefix.startsWith("V")) {
      String keepGender = gender;
      gender = number;
      number = keepGender;
    }
    String addGender = "C";
    if (splitPostag.prefix.startsWith("DA")) {
      addGender = "";
    }
    String[] synhtesized = synth.synthesize(atr, splitPostag.prefix + "[" + gender + addGender + "]"
      + "[" + number + "N" + "]" + splitPostag.suffix, true);
    if (synhtesized.length > 0) {
      return synhtesized[0];
    } else {
      return "";
    }
  }

}
