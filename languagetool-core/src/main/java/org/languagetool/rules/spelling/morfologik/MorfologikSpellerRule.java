/* LanguageTool, a natural language style checker
 * Copyright (C) 2012 Marcin Miłkowski (http://www.languagetool.org)
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

package org.languagetool.rules.spelling.morfologik;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;
import org.languagetool.AnalyzedSentence;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.JLanguageTool;
import org.languagetool.Language;
import org.languagetool.UserConfig;
import org.languagetool.languagemodel.LanguageModel;
import org.languagetool.rules.Categories;
import org.languagetool.rules.ITSIssueType;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.spelling.SpellingCheckRule;
import org.languagetool.rules.spelling.suggestions.SuggestionsChanges;
import org.languagetool.rules.spelling.suggestions.SuggestionsOrderer;
import org.languagetool.rules.spelling.suggestions.SuggestionsOrdererFeatureExtractor;
import org.languagetool.rules.spelling.suggestions.XGBoostSuggestionsOrderer;
import org.languagetool.tools.Tools;

public abstract class MorfologikSpellerRule extends SpellingCheckRule {

  protected MorfologikMultiSpeller speller1;
  protected MorfologikMultiSpeller speller2;
  protected MorfologikMultiSpeller speller3;
  protected Locale conversionLocale;

  private final SuggestionsOrderer suggestionsOrderer;
  private final boolean runningExperiment;

  private boolean ignoreTaggedWords = false;
  private boolean checkCompound = false;
  private Pattern compoundRegex = Pattern.compile("-");
  private final UserConfig userConfig;
 
  //do not use very frequent words in split word suggestions ex. to *thow ≠ tot how 
  static final int MAX_FREQUENCY_FOR_SPLITTING = 21; //0..21

  /**
   * Get the filename, e.g., <tt>/resource/pl/spelling.dict</tt>.
   */
  public abstract String getFileName();

  @Override
  public abstract String getId();

  public MorfologikSpellerRule(ResourceBundle messages, Language language) throws IOException {
    this(messages, language, null);
  }
  
  public MorfologikSpellerRule(ResourceBundle messages, Language language, UserConfig userConfig) throws IOException {
    this(messages, language, userConfig, Collections.emptyList());
  }

  public MorfologikSpellerRule(ResourceBundle messages, Language language, UserConfig userConfig, List<Language> altLanguages) throws IOException {
    this(messages, language, userConfig, altLanguages, null);
  }

  public MorfologikSpellerRule(ResourceBundle messages, Language language, UserConfig userConfig,
                               List<Language> altLanguages, LanguageModel languageModel) throws IOException {
    super(messages, language, userConfig, altLanguages, languageModel);
    this.userConfig = userConfig;
    super.setCategory(Categories.TYPOS.getCategory(messages));
    this.conversionLocale = conversionLocale != null ? conversionLocale : Locale.getDefault();
    init();
    setLocQualityIssueType(ITSIssueType.Misspelling);

    if (SuggestionsChanges.isRunningExperiment("NewSuggestionsOrderer")) {
      suggestionsOrderer = new SuggestionsOrdererFeatureExtractor(language, this.languageModel);
      runningExperiment = true;
    } else {
      runningExperiment = false;
      suggestionsOrderer = new XGBoostSuggestionsOrderer(language, languageModel);
    }
  }

  @Override
  public String getDescription() {
    return messages.getString("desc_spelling");
  }

  public void setLocale(Locale locale) {
    conversionLocale = locale;
  }

  /**
   * Skip words that are known in the POS tagging dictionary, assuming they
   * cannot be incorrect.
   */
  public void setIgnoreTaggedWords() {
    ignoreTaggedWords = true;
  }

  @Override
  public RuleMatch[] match(AnalyzedSentence sentence) throws IOException {
    List<RuleMatch> ruleMatches = new ArrayList<>();
    AnalyzedTokenReadings[] tokens = getSentenceWithImmunization(sentence).getTokensWithoutWhitespace();
    //lazy init
    if (speller1 == null) {
      String binaryDict = null;
      if (JLanguageTool.getDataBroker().resourceExists(getFileName()) || Paths.get(getFileName()).toFile().exists()) {
        binaryDict = getFileName();
      }
      if (binaryDict != null) {
        initSpeller(binaryDict);
      } else {
        // should not happen, as we only configure this rule (or rather its subclasses)
        // when we have the resources:
        return toRuleMatchArray(ruleMatches);
      }
    }
    int idx = -1;
    for (AnalyzedTokenReadings token : tokens) {
      idx++;
      if (canBeIgnored(tokens, idx, token)) {
        continue;
      }
      int startPos = token.getStartPos();
      // if we use token.getToken() we'll get ignored characters inside and speller will choke
      String word = token.getAnalyzedToken(0).getToken();
      int newRuleIdx = ruleMatches.size();
      if (tokenizingPattern() == null) {
        ruleMatches.addAll(getRuleMatches(word, startPos, sentence, ruleMatches, idx, tokens));
      } else {
        int index = 0;
        Matcher m = tokenizingPattern().matcher(word);
        while (m.find()) {
          String match = word.subSequence(index, m.start()).toString();
          ruleMatches.addAll(getRuleMatches(match, startPos + index, sentence, ruleMatches, idx, tokens));
          index = m.end();
        }
        if (index == 0) { // tokenizing char not found
          ruleMatches.addAll(getRuleMatches(word, startPos, sentence, ruleMatches, idx, tokens));
        } else {
          ruleMatches.addAll(getRuleMatches(word.subSequence(index, word.length()).toString(), startPos + index, sentence, ruleMatches, idx, tokens));
        }
      }

      if (ruleMatches.size() > newRuleIdx) {
        // matches added for current token - need to adjust for hidden characters
        int hiddenCharOffset = token.getToken().length() - word.length();
        if (hiddenCharOffset > 0) {
          for (int i = newRuleIdx; i < ruleMatches.size(); i++) {
            RuleMatch ruleMatch = ruleMatches.get(i);

            if( token.getEndPos() < ruleMatch.getToPos() ) // done by multi-token speller, no need to adjust
              continue;

            ruleMatch.setOffsetPosition(ruleMatch.getFromPos(), ruleMatch.getToPos()+hiddenCharOffset);
          }
        }
      }

    }

    return toRuleMatchArray(ruleMatches);
  }

  private void initSpeller(String binaryDict) throws IOException {
    String plainTextDict = null;
    String languageVariantPlainTextDict = null;
    if (getSpellingFileName() != null && JLanguageTool.getDataBroker().resourceExists(getSpellingFileName())) {
      plainTextDict = getSpellingFileName();
    }
    if (getLanguageVariantSpellingFileName() != null && JLanguageTool.getDataBroker().resourceExists(getLanguageVariantSpellingFileName())) {
      languageVariantPlainTextDict = getLanguageVariantSpellingFileName();
    }
    speller1 = new MorfologikMultiSpeller(binaryDict, plainTextDict, languageVariantPlainTextDict, userConfig, 1);
    speller2 = new MorfologikMultiSpeller(binaryDict, plainTextDict, languageVariantPlainTextDict, userConfig, 2);
    speller3 = new MorfologikMultiSpeller(binaryDict, plainTextDict, languageVariantPlainTextDict, userConfig, 3);
    setConvertsCase(speller1.convertsCase());
  }

  private boolean canBeIgnored(AnalyzedTokenReadings[] tokens, int idx, AnalyzedTokenReadings token) throws IOException {
    return token.isSentenceStart() ||
           token.isImmunized() ||
           token.isIgnoredBySpeller() ||
           isUrl(token.getToken()) ||
           isEMail(token.getToken()) ||
           (ignoreTaggedWords && token.isTagged() && !isProhibited(token.getToken())) ||
           ignoreToken(tokens, idx);
  }


  /**
   * @return true if the word is misspelled
   * @since 2.4
   */
  protected boolean isMisspelled(MorfologikMultiSpeller speller, String word) {
    if (!speller.isMisspelled(word)) {
      return false;
    }

    if (checkCompound && compoundRegex.matcher(word).find()) {
      String[] words = compoundRegex.split(word);
      for (String singleWord: words) {
        if (speller.isMisspelled(singleWord)) {
          return true;
        }
      }
      return false;
    }

    return true;
  }
  
  protected int getFrequency(MorfologikMultiSpeller speller, String word) {
    return speller.getFrequency(word);
  }

  protected List<RuleMatch> getRuleMatches(String word, int startPos, AnalyzedSentence sentence, List<RuleMatch> ruleMatchesSoFar, int idx, AnalyzedTokenReadings[] tokens) throws IOException {
    // We create only one rule match. 
    // Several rule matches on the same word or words can not be shown to the user.
    List<RuleMatch> ruleMatches = new ArrayList<>();
    RuleMatch ruleMatch = null;
    
    if (!isMisspelled(speller1, word) && !isProhibited(word)) {
      return ruleMatches;
    }
    
    //the current word is already dealt with in the previous match, so do nothing
    if (ruleMatchesSoFar.size() > 0 && ruleMatchesSoFar.get(ruleMatchesSoFar.size() - 1).getToPos() > startPos) {
      return ruleMatches;
    }
    
    String beforeSuggestionStr = ""; //to be added before the suggestion if there is a suggestion for a split word
    String afterSuggestionStr = "";  //to be added after
    
    // Check for split word with previous word
    if (idx > 0) {
      String prevWord = tokens[idx - 1].getToken();
      if (prevWord.length() > 0 && !prevWord.matches(".*\\d.*")
          && getFrequency(speller1, prevWord) < MAX_FREQUENCY_FOR_SPLITTING) {
        int prevStartPos = tokens[idx - 1].getStartPos();
        // "thanky ou" -> "thank you"
        String sugg1a = prevWord.substring(0, prevWord.length() - 1);
        String sugg1b = prevWord.substring(prevWord.length() - 1) + word;
        if (sugg1a.length() > 1 && sugg1b.length() > 2 && !isMisspelled(speller1, sugg1a)
            && !isMisspelled(speller1, sugg1b)
            && getFrequency(speller1, sugg1a) + getFrequency(speller1, sugg1b) > getFrequency(speller1, prevWord)) {
          ruleMatch = createWrongSplitMatch(sentence, ruleMatchesSoFar, startPos, word, sugg1a, sugg1b, prevStartPos);
          beforeSuggestionStr = prevWord + " ";
        }
        // "than kyou" -> "thank you" ; but not "She awaked" -> "Shea waked"
        String sugg2a = prevWord + word.substring(0, 1);
        String sugg2b = word.substring(1);
        if (sugg2a.length() > 1 && sugg2b.length() > 2 && !isMisspelled(speller1, sugg2a)
            && !isMisspelled(speller1, sugg2b)) {
          if (ruleMatch == null) {
            if (getFrequency(speller1, sugg2a) + getFrequency(speller1, sugg2b) > getFrequency(speller1, prevWord)) {
              ruleMatch = createWrongSplitMatch(sentence, ruleMatchesSoFar, startPos, word, sugg2a, sugg2b,
                  prevStartPos);
              beforeSuggestionStr = prevWord + " ";
            }
          } else {
            ruleMatch.addSuggestedReplacement((sugg2a + " " + sugg2b).trim());
          }
        }
        // "g oing-> "going"
        String sugg = prevWord + word;
        if (word.equals(word.toLowerCase()) && !isMisspelled(speller1, sugg)) {
          if (ruleMatch == null) {
            if (getFrequency(speller1, sugg) >= getFrequency(speller1, prevWord)) {
              ruleMatch = new RuleMatch(this, sentence, prevStartPos, startPos + word.length(),
                  messages.getString("spelling"), messages.getString("desc_spelling_short"));
              beforeSuggestionStr = prevWord + " ";
              ruleMatch.setSuggestedReplacement(sugg);
            }
          } else {
            ruleMatch.addSuggestedReplacement(sugg);
          }
        }
        if (ruleMatch != null && isMisspelled(speller1, prevWord)) {
          ruleMatches.add(ruleMatch);
          return ruleMatches;
        }
      }
    }
        
    // Check for split word with next word
    if (ruleMatch == null && idx < tokens.length - 1) {
      String nextWord = tokens[idx + 1].getToken();
      if (nextWord.length() > 0 && !nextWord.matches(".*\\d.*")
          && getFrequency(speller1, nextWord) < MAX_FREQUENCY_FOR_SPLITTING) {
        int nextStartPos = tokens[idx + 1].getStartPos();
        String sugg1a = word.substring(0, word.length() - 1);
        String sugg1b = word.substring(word.length() - 1) + nextWord;
        if (sugg1a.length() > 1 && sugg1b.length() > 2 && !isMisspelled(speller1, sugg1a) && !isMisspelled(speller1, sugg1b) &&
            getFrequency(speller1, sugg1a) + getFrequency(speller1, sugg1b) > getFrequency(speller1, nextWord)) {
          ruleMatch = createWrongSplitMatch(sentence, ruleMatchesSoFar, nextStartPos, nextWord, sugg1a, sugg1b, startPos);
          afterSuggestionStr = " " + nextWord;
        }
        String sugg2a = word + nextWord.substring(0, 1);
        String sugg2b = nextWord.substring(1);
        if (sugg2a.length() > 1 && sugg2b.length() > 2 && !isMisspelled(speller1, sugg2a) && !isMisspelled(speller1, sugg2b)) {
          if (ruleMatch == null) {
            if (getFrequency(speller1, sugg2a) + getFrequency(speller1, sugg2b) > getFrequency(speller1, nextWord)) {
              ruleMatch = createWrongSplitMatch(sentence, ruleMatchesSoFar, nextStartPos, nextWord, sugg2a, sugg2b, startPos);
              afterSuggestionStr = " " + nextWord;
            }
          } else {
            ruleMatch.addSuggestedReplacement((sugg2a + " " + sugg2b).trim());
          }
        }
        String sugg = word + nextWord;
        if (nextWord.equals(nextWord.toLowerCase()) && !isMisspelled(speller1, sugg)) {
          if (ruleMatch == null) {
            if (getFrequency(speller1, sugg) >= getFrequency(speller1, nextWord)) {
              ruleMatch = new RuleMatch(this, sentence, startPos, nextStartPos + nextWord.length(),
                  messages.getString("spelling"), messages.getString("desc_spelling_short"));
              afterSuggestionStr = " " + nextWord;
              ruleMatch.setSuggestedReplacement(sugg);
            }
          } else {
            ruleMatch.addSuggestedReplacement(sugg);
          }
        }
        if (ruleMatch != null && isMisspelled(speller1, nextWord)) {
          ruleMatches.add(ruleMatch);
          return ruleMatches;
        }
      }
    }
 

    if (ruleMatch == null) {
      Language acceptingLanguage = acceptedInAlternativeLanguage(word);
      if (acceptingLanguage != null) {
        // e.g. "Der Typ ist in UK echt famous" -> could be German 'famos'
        ruleMatch = new RuleMatch(this, sentence, startPos, startPos + word.length(),
                Tools.i18n(messages, "accepted_in_alt_language", word, messages.getString(acceptingLanguage.getShortCode())));
        ruleMatch.setType(RuleMatch.Type.Hint);
      } else {
        ruleMatch = new RuleMatch(this, sentence, startPos, startPos + word.length(), messages.getString("spelling"),
                messages.getString("desc_spelling_short"));
      }
    }
    boolean fullResults = SuggestionsChanges.getInstance() != null &&
      SuggestionsChanges.getInstance().getCurrentExperiment() != null &&
      (boolean) SuggestionsChanges.getInstance().getCurrentExperiment()
        .parameters.getOrDefault("fullSuggestionCandidates", Boolean.FALSE);

    if (userConfig == null || userConfig.getMaxSpellingSuggestions() == 0 
        || ruleMatchesSoFar.size() <= userConfig.getMaxSpellingSuggestions()) {
      List<String> defaultSuggestions = speller1.getSuggestionsFromDefaultDicts(word);
      List<String> userSuggestions = speller1.getSuggestionsFromUserDicts(word);
      //System.out.println("speller1: " + suggestions);
      if (word.length() >= 3 && (fullResults || defaultSuggestions.isEmpty())) {
        // speller1 uses a maximum edit distance of 1, it won't find suggestion for "garentee", "greatful" etc.
        //System.out.println("speller2: " + speller2.getSuggestions(word));
        defaultSuggestions.addAll(speller2.getSuggestionsFromDefaultDicts(word));
        userSuggestions.addAll(speller2.getSuggestionsFromUserDicts(word));
        if (word.length() >= 5 && (fullResults || defaultSuggestions.isEmpty())) {
          //System.out.println("speller3: " + speller3.getSuggestions(word));
          defaultSuggestions.addAll(speller3.getSuggestionsFromDefaultDicts(word));
          userSuggestions.addAll(speller3.getSuggestionsFromUserDicts(word));
        }
      }
      //System.out.println("getAdditionalTopSuggestions(suggestions, word): " + getAdditionalTopSuggestions(suggestions, word));
      defaultSuggestions.addAll(0, getAdditionalTopSuggestions(defaultSuggestions, word));
      //System.out.println("getAdditionalSuggestions(suggestions, word): " + getAdditionalSuggestions(suggestions, word));
      defaultSuggestions.addAll(getAdditionalSuggestions(defaultSuggestions, word));

      if (!(defaultSuggestions.isEmpty() && userSuggestions.isEmpty())) {
        filterSuggestions(defaultSuggestions);
        filterDupes(userSuggestions);
        defaultSuggestions = orderSuggestions(defaultSuggestions, word);
        
        defaultSuggestions = joinBeforeAfterSuggestions(defaultSuggestions, beforeSuggestionStr, afterSuggestionStr);
        userSuggestions = joinBeforeAfterSuggestions(userSuggestions, beforeSuggestionStr, afterSuggestionStr);
        // use suggestionsOrderer only w/ A/B - Testing or manually enabled experiments
        if (runningExperiment) {
          addSuggestionsToRuleMatch(word,
            userSuggestions, defaultSuggestions, suggestionsOrderer, ruleMatch);
        } else if (userConfig != null && userConfig.getAbTest() != null &&
          userConfig.getAbTest().equals("SuggestionsRanker") &&
          suggestionsOrderer.isMlAvailable() && userConfig.getTextSessionId() != null) {
          boolean testingA = userConfig.getTextSessionId() % 2 == 0;
          if (testingA) {
            addSuggestionsToRuleMatch(word, userSuggestions, defaultSuggestions, null, ruleMatch);
          } else {
            addSuggestionsToRuleMatch(word, userSuggestions, defaultSuggestions, suggestionsOrderer, ruleMatch);
          }
        } else {
          addSuggestionsToRuleMatch(word, userSuggestions, defaultSuggestions, null, ruleMatch);
        }
      }
    } else {
      // limited to save CPU
      ruleMatch.setSuggestedReplacement(messages.getString("too_many_errors"));
    }
 
    ruleMatches.add(ruleMatch);
    return ruleMatches;
  }

  /**
   * Get the regular expression pattern used to tokenize
   * the words as in the source dictionary. For example,
   * it may contain a hyphen, if the words with hyphens are
   * not included in the dictionary
   * @return A compiled {@link Pattern} that is used to tokenize words or {@code null}.
   */
  @Nullable
  public Pattern tokenizingPattern() {
    return null;
  }

  protected List<String> orderSuggestions(List<String> suggestions, String word) {
    return suggestions;
  }

  private List<String> orderSuggestions(List<String> suggestions, String word, AnalyzedSentence sentence, int startPos) {
    List<String> orderedSuggestions;
    if (userConfig != null && userConfig.getAbTest() != null && userConfig.getAbTest().equals("SuggestionsOrderer") &&
      suggestionsOrderer.isMlAvailable() && userConfig.getTextSessionId() != null) {
      boolean logGroup = Math.random() < 0.01;
      if (logGroup) {
        System.out.print("Running A/B-Test for SuggestionsOrderer ->");
      }
      if (userConfig.getTextSessionId() % 2 == 0) {
        if (logGroup) {
          System.out.println("in group A (using new ordering)");
        }
        orderedSuggestions = suggestionsOrderer.orderSuggestionsUsingModel(suggestions, word, sentence, startPos);
      } else {
        if (logGroup) {
          System.out.println("in group B (using old ordering)");
        }
        orderedSuggestions = orderSuggestions(suggestions, word);
      }
    } else {
      if (suggestionsOrderer.isMlAvailable()) {
        orderedSuggestions = suggestionsOrderer.orderSuggestionsUsingModel(suggestions, word, sentence, startPos);
      } else {
        orderedSuggestions = orderSuggestions(suggestions, word);
      }
    }
    return orderedSuggestions;
  }

  /**
   * @param checkCompound If true and the word is not in the dictionary
   * it will be split (see {@link #setCompoundRegex(String)})
   * and each component will be checked separately
   * @since 2.4
   */
  protected void setCheckCompound(boolean checkCompound) {
    this.checkCompound = checkCompound;
  }

  /**
   * @param compoundRegex see {@link #setCheckCompound(boolean)}
   * @since 2.4
   */
  protected void setCompoundRegex(String compoundRegex) {
    this.compoundRegex = Pattern.compile(compoundRegex);
  }

  /**
   * Checks whether a given String consists only of surrogate pairs.
   * @param word to be checked
   * @since 4.2
   */
  protected boolean isSurrogatePairCombination (String word) {
    if (word.length() > 1 && word.length() % 2 == 0 && word.codePointCount(0, word.length()) != word.length()) {
      // some symbols such as emojis (😂) have a string length that equals 2
      boolean isSurrogatePairCombination = true;
      for (int i = 0; i < word.length() && isSurrogatePairCombination; i += 2) {
        isSurrogatePairCombination &= Character.isSurrogatePair(word.charAt(i), word.charAt(i + 1));
      }
      return isSurrogatePairCombination;
    }
    return false;
  }

  /**
   * Ignore surrogate pairs (emojis) 
   * @since 4.3 
   * @see org.languagetool.rules.spelling.SpellingCheckRule#ignoreWord(java.lang.String)
   */
  @Override
  protected boolean ignoreWord(String word) throws IOException {
    return super.ignoreWord(word) || isSurrogatePairCombination(word);
  }
  
  /**
   * 
   * Join strings before and after a suggestion.
   * Used when there is also suggestion for split words
   * Ex. to thow > tot how | to throw
   * 
   */
  private List<String> joinBeforeAfterSuggestions(List<String> suggestionsList, String beforeSuggestionStr,
      String afterSuggestionStr) {
    List<String> newSuggestionsList = new ArrayList<>();
    for (String str : suggestionsList) {
      newSuggestionsList.add(beforeSuggestionStr + str + afterSuggestionStr);
    }
    return newSuggestionsList;
  }
}
