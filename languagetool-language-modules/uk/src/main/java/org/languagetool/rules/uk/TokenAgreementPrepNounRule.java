/* LanguageTool, a natural language style checker 
 * Copyright (C) 2013 Andriy Rysin
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
package org.languagetool.rules.uk;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.Nullable;
import org.languagetool.AnalyzedSentence;
import org.languagetool.AnalyzedToken;
import org.languagetool.AnalyzedTokenReadings;
import org.languagetool.Language;
import org.languagetool.rules.Categories;
import org.languagetool.rules.Rule;
import org.languagetool.rules.RuleMatch;
import org.languagetool.rules.uk.LemmaHelper.Dir;
import org.languagetool.synthesis.Synthesizer;
import org.languagetool.tagging.uk.IPOSTag;
import org.languagetool.tagging.uk.PosTagHelper;

/**
 * A rule that checks if preposition and a noun agree on inflection etc
 * 
 * @author Andriy Rysin
 */
public class TokenAgreementPrepNounRule extends Rule {
  
  static final List<String> QUOTES = Arrays.asList("«", "\"", "„", "“");
  static final List<String> Z_ZI_IZ = Arrays.asList("з", "зі", "із");
  private static final List<String> Z_ZI_IZ_ZO = Arrays.asList("з", "зі", "із", "зо");
  private static final Pattern NOUN_ANIM_V_NAZ_PATTERN = Pattern.compile("noun:anim:.:v_naz.*");
  private static final String VIDMINOK_SUBSTR = ":v_";
  private static final Pattern VIDMINOK_REGEX = Pattern.compile(":(v_[a-z]+)");
  private static final String reqAnimInanimRegex = ":r(?:in)?anim";
  private static final Pattern REQ_ANIM_INANIM_PATTERN = Pattern.compile(reqAnimInanimRegex);

  private final Synthesizer synthesizer;
  private final Language ukrainian;

  static class State {
    int prepPos;
    AnalyzedTokenReadings prepTokenReadings = null;
    boolean ziZnaRemoved = false;
    Set<String> posTagsToFind;
  }

  
  public TokenAgreementPrepNounRule(ResourceBundle messages, Language ukrainian) throws IOException {
    super.setCategory(Categories.MISC.getCategory(messages));
    this.ukrainian = ukrainian;
    this.synthesizer = ukrainian.getSynthesizer();
  }

  @Override
  public final String getId() {
    return "UK_PREP_NOUN_INFLECTION_AGREEMENT";
  }

  @Override
  public String getDescription() {
    return "Узгодження прийменника та іменника у реченні";
  }

  public String getShort() {
    return "Узгодження прийменника та іменника";
  }
  
  @Override
  public final RuleMatch[] match(AnalyzedSentence sentence) throws IOException {
    List<RuleMatch> ruleMatches = new ArrayList<>();
    AnalyzedTokenReadings[] tokens = sentence.getTokensWithoutWhitespace();
    State state = null;
    
    for (int i = 1; i < tokens.length; i++) {
      AnalyzedTokenReadings tokenReadings = tokens[i];

      String posTag = tokenReadings.getAnalyzedToken(0).getPOSTag();
      String thisToken = tokenReadings.getCleanToken();

      // через, м’яко кажучи, невеликої популярності
//      if( prepTokenReadings != null ) {
//        int insertEndPos = findInsertEnd(prepTokenReadings, tokens, i, false);
//        if( insertEndPos > 0 ) {
//          i=insertEndPos;
//          continue;
//        }
//      }

      if( QUOTES.contains(tokens[i].getCleanToken()) ) {
        continue;
      }

      if (posTag == null) {
        state = null;
        continue;
      }

      // часто вживають укр. В замість лат.: гепатит В
      // first token is always SENT_START
      if( i > 1
          && thisToken.length() == 1 
          && Character.isUpperCase(thisToken.charAt(0)) 
          && tokenReadings.isWhitespaceBefore() 
          && tokens[i-1].getToken().matches(".*[а-яіїєґ0-9]")) {
        state = null;
        continue;
      }

      AnalyzedToken multiwordReqToken = getMultiwordToken(tokenReadings);
      if( multiwordReqToken != null ) {

        if (Z_ZI_IZ.contains(tokenReadings.getCleanToken().toLowerCase()) 
            && multiwordReqToken.getLemma().startsWith("згідно ") ) { // напр. "згідно з"
          posTag = multiwordReqToken.getPOSTag(); // "rv_oru";
          state = new State();
          state.prepTokenReadings = tokenReadings;
          state.prepPos = i;
          continue;
        }
        else {
          if( posTag.startsWith(IPOSTag.prep.name()) ) {
            state = null;
            continue;
          }

          String mwPosTag = multiwordReqToken.getPOSTag();
          if( ! mwPosTag.contains("adv") && ! mwPosTag.contains("insert") ) {
            state = null;
          }
        }

        continue;
      }


      String token = tokenReadings.getCleanToken();
      if( posTag.startsWith(IPOSTag.prep.name()) ) {
        String prep = token.toLowerCase();

        // з понад тисячі
        if( prep.equals("понад") )
          continue;

        if( prep.matches("шляхом|од|поруч|ради") ) {
          state = null;
          continue;
        }

        state = new State();
        state.prepTokenReadings = tokenReadings;
        state.prepPos = i;
        continue;
      }

      if( state == null )
        continue;

      // з Ван Дамом
      if( Arrays.asList("ван").contains(thisToken.toLowerCase()) ) {
        // prepTokenReadings = null;
        continue;
      }
      if( Arrays.asList("Фон").contains(thisToken) ) {
        // prepTokenReadings = null;
        continue;
      }
      // до та після
      if( "та".equals(thisToken.toLowerCase()) ) {
        state = null;
        continue;
      }


      // Do actual check

      state.posTagsToFind = new LinkedHashSet<>();
      String prep = state.prepTokenReadings.getAnalyzedToken(0).getLemma();
//      state.prepTokenReadings = state.prepAnalyzedTokenReadings.getReadings();

      // замість Андрій вибрали Федір
      if( prep.equals("замість") ) {
        state.posTagsToFind.add("v_naz");
      }
      // що за ганебна послідовність
      else if( prep.equals("за") ) {
        if( i > 1 
            && tokens[state.prepPos-1].getCleanToken().equalsIgnoreCase("що") ) {
          state.posTagsToFind.add("v_naz");
        }
      }

      // писав про «Сновиди»
      // замість «урані»
      if( QUOTES.contains(tokens[i-1].getCleanToken()) ) {
        if( LemmaHelper.isCapitalized(tokens[i].getCleanToken()) 
            || state.prepTokenReadings.getCleanToken().equalsIgnoreCase("замість")) {
          state = null;
          continue;
        }
        
        state.posTagsToFind.add("v_naz");
      }

      Set<String> expectedCases = CaseGovernmentHelper.getCaseGovernments(state.prepTokenReadings, IPOSTag.prep.name());

      // згідно з документа
      if( Z_ZI_IZ_ZO.contains(prep) ) {
        if( "нізвідки".equalsIgnoreCase(tokens[i].getCleanToken()) ) {
          state = null;
          continue;
        }
      
        if( Z_ZI_IZ.contains(prep)
            && i >= 3 
            && tokens[i-2].getCleanToken().equalsIgnoreCase("згідно")) {
          expectedCases = new HashSet<>(Arrays.asList("v_oru"));
        } else if( ! isLikelyApproxWithZi(tokens, i, state) ) {
          expectedCases.remove("v_zna");
          state.ziZnaRemoved = true;
        }
      }

      expectedCases.remove("v_inf"); // we don't care about rv_inf here
      state.posTagsToFind.addAll(expectedCases);

      RuleException exception = TokenAgreementPrepNounExceptionHelper.getExceptionStrong(tokens, i, state.prepTokenReadings);
      switch( exception.type ) {
      case exception:
        state = null;
        continue;
      case skip:
        i += exception.skip;
        continue;
      case none:
        break;
      }

      
      if( PosTagHelper.hasPosTagPart(tokenReadings, ":v_") ) {

        // домовився за їх. - ненормативна форма
        List<AnalyzedToken> pronPosNounReadings = tokenReadings.getReadings().stream()
            .filter(r -> PosTagHelper.hasPosTag(r, Pattern.compile("noun:unanim:.:v_rod.*pron.*")) 
                && Arrays.asList("вони", "він", "вона", "воно").contains(r.getLemma()))
            .collect(Collectors.toList());

        // нього-таки тощо
        //TODO: |його|її
        if( pronPosNounReadings.size() > 0 && ! thisToken.toLowerCase().matches("(них|нього|неї)(-[а-я]+)?") ) {
          if( i < tokens.length - 1 
              && (PosTagHelper.hasPosTag(tokens[i+1], Pattern.compile("(noun|adj|adv|part|num|conj:coord|noninfl).*"))
                  || StringUtils.defaultIfBlank(tokens[i+1].getCleanToken(), "").matches("[\"«„“/$€…]|[a-zA-Z'-]+") ) ) {
            // test next
            // при його ділянці 
            continue;
          }
          else {
            int insertEndPos = findInsertEnd(state.prepTokenReadings, tokens, i+1, true);
            if( insertEndPos > 0 ) {
              i=insertEndPos;
              continue;
            }
            
            RuleMatch potentialRuleMatch = createRuleMatch(state, sentence, tokens, i);
            ruleMatches.add(potentialRuleMatch);
            state = null;
            continue;
          }
        }

        List<AnalyzedToken> pronPosAdjReadings = tokenReadings.getReadings().stream()
            .filter(r -> PosTagHelper.hasPosTag(r, Pattern.compile("adj.*pron:pos(?!:bad).*")) 
                && Arrays.asList("їх", "його", "її").contains(r.getLemma()))
            .collect(Collectors.toList());

        // to detect: завдяки його зусиллі
        if( pronPosAdjReadings.size() > 0 ) {

          if (! TokenAgreementPrepNounRule.hasVidmPosTag(state.posTagsToFind, pronPosAdjReadings)) {
            RuleMatch potentialRuleMatch = createRuleMatch(state, sentence, tokens, i);
            ruleMatches.add(potentialRuleMatch);
            state = null;
            continue;
          }

          if( i < tokens.length - 1 ) {
            // test next
            // при їхній ділянці 
            continue;
          }
        }
        else if ( thisToken.equals("їх") ) {
          RuleMatch potentialRuleMatch = createRuleMatch(state, sentence, tokens, i);
          ruleMatches.add(potentialRuleMatch);
          state = null;
          continue;
        }

        if( hasVidmPosTag(state.posTagsToFind, tokenReadings) ) {
          state = null;
          continue;
        }

        exception = TokenAgreementPrepNounExceptionHelper.getExceptionNonInfl(tokens, i, state);
        switch( exception.type ) {
        case exception:
          state = null;
          continue;
        case skip:
          i += exception.skip;
          continue;
        case none:
          break;
        }

        exception = TokenAgreementPrepNounExceptionHelper.getExceptionInfl(tokens, i, state);
        switch( exception.type ) {
        case exception:
          state = null;
          continue;
        case skip:
          i += exception.skip;
          continue;
        case none:
          break;
        }

        RuleMatch potentialRuleMatch = createRuleMatch(state, sentence, tokens, i);
        ruleMatches.add(potentialRuleMatch);
      }
      else { // no _v found

        exception = TokenAgreementPrepNounExceptionHelper.getExceptionNonInfl(tokens, i, state);
        switch( exception.type ) {
        case exception:
          state = null;
          continue;
        case skip:
          i += exception.skip;
          continue;
        case none:
          break;
        }

      }
      state = null;
    }

    return toRuleMatchArray(ruleMatches);
  }

  private static final List<String> approxLemmas = Arrays.asList(
      "розмір", "величина", "товщина", "вартість", "ріст", "зріст", "висота", "глибина", "діаметр", "вага", "обсяг", "площа",
      "приблизно", "десь", "завбільшки", "завширшки", "завдовжки", "завтовшки", "заввишки", "завглибшки");
  private static final Pattern approxTag = Pattern.compile("noun.*v_oru.*|adv.*|part.*");
  private static final Set<String> lemmas = new HashSet<>(LemmaHelper.TIME_LEMMAS);
  static {
    lemmas.addAll(LemmaHelper.DISTANCE_LEMMAS);
    lemmas.addAll(LemmaHelper.PSEUDO_NUM_LEMMAS);
    lemmas.addAll(Arrays.asList("ложка", "ложечка"));
  }

  private boolean isLikelyApproxWithZi(AnalyzedTokenReadings[] tokens, int i, State state) {
    // TODO: ледь не

    // з 2-поверховий, з 10-поверхівку
    if( tokens[i].getCleanToken().matches(".*поверх(ов|ів).*") )
      return true;

    return PosTagHelper.hasPosTag(tokens[i], Pattern.compile("noun:inanim:[fnm]:v_zna.*num.*|num.*"))
        || LemmaHelper.hasLemma(tokens[i], lemmas, Pattern.compile("noun:inanim:[mnf]:v_zna.*"))
        || (i < tokens.length - 1 
            && PosTagHelper.hasPosTag(tokens[i], Pattern.compile("adj:[mnf]:v_zna.*"))
            && LemmaHelper.hasLemma(tokens[i+1], lemmas, Pattern.compile("noun:inanim:[mnf]:v_zna.*")))
        || LemmaHelper.hasLemma(tokens[state.prepPos-1], approxLemmas, approxTag)
        || (i < tokens.length - 1 
            && LemmaHelper.hasLemma(tokens[i+1], approxLemmas, approxTag));
  }

  private static int findInsertEnd(AnalyzedTokenReadings prepTokenReadings, AnalyzedTokenReadings[] tokens, int i, boolean lookForPart) {
    if( i >= tokens.length - 2 )
      return -1;
    
    int nextPos = i;
    AnalyzedTokenReadings tokenReadings = tokens[i];
    
    if( i > tokens.length - 2 )
      return -1;

    if( tokenReadings.getCleanToken().matches("же?") ) {
      nextPos = i+1;
    }

    if( nextPos > tokens.length - 3 )
      return nextPos==i ? -1 : nextPos-1;

    if( tokenReadings.isPosTagUnknown() && tokenReadings.getCleanToken().matches("[,(]") ) {
      int commaPos = LemmaHelper.tokenSearch(tokens, i+1, (String)null, Pattern.compile("[,)]"), null, Dir.FORWARD);
      if( commaPos > i+1 && commaPos < i+6 && commaPos < tokens.length-1 && ! tokens[commaPos+1].getCleanToken().equals("що") ) {
        if( tokenReadings.getCleanToken().replace('(', ')').equals(tokens[commaPos].getCleanToken()) )
          return commaPos;
      }
    }
    return nextPos==i ? -1 : nextPos-1;
  }

  static boolean hasVidmPosTag(Collection<String> posTagsToFind, AnalyzedTokenReadings tokenReadings) {
    return hasVidmPosTag(posTagsToFind, tokenReadings.getReadings());
  }

  static boolean hasVidmPosTag(Collection<String> posTagsToFind, List<AnalyzedToken> tokenReadings) {
    boolean vidminokFound = false;  // because POS dictionary is not complete

    for(AnalyzedToken token: tokenReadings) {
      String posTag = token.getPOSTag();

      if( posTag == null ) { // && ! ".".equals(tokenReadings.get(0).getToken()) ) {
        if( tokenReadings.size() == 1)
          return true;

        continue;
      }

      // shortcut
      if( posTag.contains(PosTagHelper.NO_VIDMINOK_SUBSTR) )
        return true;

      if( posTag.contains(VIDMINOK_SUBSTR) ) {
        vidminokFound = true;

        for(String posTagToFind: posTagsToFind) {
          if ( posTag.contains(posTagToFind) )
            return true;
        }
      }
    }

    return ! vidminokFound; //false;
  }

  private RuleMatch createRuleMatch(State state, AnalyzedSentence sentence, AnalyzedTokenReadings[] tokens, int i) throws IOException {
    AnalyzedTokenReadings tokenReadings = tokens[i];
    String tokenString = tokenReadings.getCleanToken().toLowerCase();
    
    List<String> suggestions = new ArrayList<>();
    
    String requiredPostTagsRegEx = ":(" + String.join("|", state.posTagsToFind) + ")";
    for (AnalyzedToken analyzedToken: tokenReadings.getReadings()) {
    
      String oldPosTag = analyzedToken.getPOSTag();
      
      if( oldPosTag == null )
        continue;
      
      String requiredPostTagsRegExToApply = requiredPostTagsRegEx;

      Matcher matcher = REQ_ANIM_INANIM_PATTERN.matcher(oldPosTag);
      if( matcher.find() ) {
        requiredPostTagsRegExToApply += matcher.group(0);
      }
      else {
        requiredPostTagsRegExToApply += "(?:" + reqAnimInanimRegex + ")?";
      }

      String posTag = oldPosTag.replaceFirst(":v_[a-z]+", requiredPostTagsRegExToApply);

      try {
        String[] synthesized = synthesizer.synthesize(analyzedToken, posTag, true);

        suggestions.addAll( Arrays.asList(synthesized) );
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    
    if( suggestions.size() > 0 ) {  // remove duplicates
      suggestions = new ArrayList<>(new LinkedHashSet<>(suggestions));
    }

    List<String> reqVidminkyNames = new ArrayList<>();
    for (String vidm: state.posTagsToFind) {
      reqVidminkyNames.add(PosTagHelper.VIDMINKY_MAP.get(vidm));
    }

    List<String> foundVidminkyNames = new ArrayList<>();
    for (AnalyzedToken token: tokenReadings) {
      String posTag2 = token.getPOSTag();
      if( posTag2 != null && posTag2.contains(VIDMINOK_SUBSTR) ) {
        String vidmName = PosTagHelper.VIDMINKY_MAP.get(posTag2.replaceFirst("^.*"+VIDMINOK_REGEX+".*$", "$1"));
        if( foundVidminkyNames.contains(vidmName) ) {
          if (posTag2.contains(":p:")) {
            vidmName = vidmName + " (мн.)";
            foundVidminkyNames.add(vidmName);
          }
          // else skip dup
        }
        else {
          foundVidminkyNames.add(vidmName);
        }
      }
    }

    String msg = MessageFormat.format("Прийменник «{0}» вимагає іншого відмінка: {1}, а знайдено: {2}", 
        state.prepTokenReadings.getToken(), String.join(", ", reqVidminkyNames), String.join(", ", foundVidminkyNames));

    if( state.ziZnaRemoved ) {
      msg += ". Але з.в. вимагається у випадках порівнянн предметів.";
    }

    if( state.posTagsToFind.contains("v_rod")
        && tokens[i].getToken().matches(".*[ую]")
        && PosTagHelper.hasPosTag(tokenReadings.getReadings(), Pattern.compile("noun.*?:m:v_dav.*")) ) {
      msg += CaseGovernmentHelper.USED_U_INSTEAD_OF_A_MSG;
    }
    else if( tokenString.equals("їх") && requiredPostTagsRegEx != null ) {
      msg += ". Можливо, тут потрібно присвійний займенник «їхній» або нормативна форма р.в. «них»?";
      try {
        String newYihPostag = "adj:p" + requiredPostTagsRegEx + ".*";
        String[] synthesized = synthesizer.synthesize(new AnalyzedToken("їхній", "adj:m:v_naz.*:pron:pos", "їхній"), newYihPostag, true);
        suggestions.addAll( Arrays.asList(synthesized) );
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    else if( (tokenString.equals("його") || tokenString.equals("її")) && requiredPostTagsRegEx != null ) {
      String repl = tokenString.equals("його") ? "нього" : "неї";
      msg += ". Можливо, тут потрібно присвійний займенник «" + repl + "»?";
      try {
        String newYihPostag = "adj:p" + requiredPostTagsRegEx + ".*";
        String[] synthesized = synthesizer.synthesize(new AnalyzedToken("їхній", "adj:m:v_naz.*:pron:pos", "їхній"), newYihPostag, true);
        suggestions.addAll( Arrays.asList(synthesized) );
        suggestions.add(repl);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    else if( state.prepTokenReadings.getCleanToken().equalsIgnoreCase("о") ) {
      for(AnalyzedToken token: tokenReadings.getReadings()) {
        if( PosTagHelper.hasPosTag(token, NOUN_ANIM_V_NAZ_PATTERN) ) {
          msg += ". Можливо, тут «о» — це вигук і потрібно кличний відмінок?";
          try {
            String newPostag = token.getPOSTag().replace("v_naz", "v_kly");
            String[] synthesized = synthesizer.synthesize(token, newPostag, false);
            for (String string : synthesized) {
              if( ! string.equals(token.getToken()) && ! suggestions.contains(string) ) {
                suggestions.add( string );
              }
            }
            break;
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      }
    }
    else if( PosTagHelper.hasPosTagStart(tokens[i-1], "adv")) {
      String mergedToken = state.prepTokenReadings.getCleanToken() + tokens[i-1].getCleanToken();
      List<AnalyzedTokenReadings> mergedTagged = ukrainian.getTagger().tag(Arrays.asList(mergedToken));
      if( PosTagHelper.hasPosTagStart(mergedTagged.get(0), "adv") ) {
        msg += ". Можливо, прийменник і прислівник мають бути одним словом?";
//        suggestions.add(mergedToken);
      }
      
    }

    RuleMatch potentialRuleMatch = new RuleMatch(this, sentence, tokenReadings.getStartPos(), tokenReadings.getEndPos(), msg, getShort());

    potentialRuleMatch.setSuggestedReplacements(suggestions);

    return potentialRuleMatch;
  }

  @Nullable
  private static AnalyzedToken getMultiwordToken(AnalyzedTokenReadings analyzedTokenReadings) {
      for(AnalyzedToken analyzedToken: analyzedTokenReadings) {
        String posTag = analyzedToken.getPOSTag();
        if( posTag != null && posTag.startsWith("<") )
          return analyzedToken;
      }
      return null;
  }

}
