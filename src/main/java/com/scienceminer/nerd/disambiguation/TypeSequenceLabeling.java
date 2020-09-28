package com.scienceminer.nerd.disambiguation;

import org.apache.commons.io.FileUtils;

import com.scienceminer.nerd.kb.LowerKnowledgeBase;
import com.scienceminer.nerd.kb.UpperKnowledgeBase;
import com.scienceminer.nerd.training.*;
import com.scienceminer.nerd.exceptions.*;
import com.scienceminer.nerd.kb.model.*;
import com.scienceminer.nerd.features.FeaturesVectorDeepType;
import com.scienceminer.nerd.utilities.mediaWiki.MediaWikiParser;

import org.grobid.core.GrobidModels;
import org.grobid.core.analyzers.GrobidAnalyzer;
import org.grobid.core.lang.Language;
import org.grobid.core.engines.label.TaggingLabel;
import org.grobid.core.engines.label.TaggingLabels;
import org.grobid.core.engines.tagging.GrobidCRFEngine;
import org.grobid.core.engines.AbstractParser;
import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.factory.GrobidFactory;
import org.grobid.core.features.FeatureFactory;
import org.grobid.core.layout.LayoutToken;
import org.grobid.core.tokenization.TaggingTokenCluster;
import org.grobid.core.tokenization.TaggingTokenClusteror;
import org.grobid.core.utilities.*;
import org.grobid.core.utilities.counters.CntManager;
import org.grobid.core.utilities.counters.impl.CntManagerFactory;
import org.grobid.core.lexicon.FastMatcher;
import org.grobid.core.features.FeatureFactory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;
import java.util.regex.*;

import static org.apache.commons.lang3.StringUtils.*;
import org.apache.commons.lang3.tuple.Pair;

import com.scienceminer.nerd.utilities.NerdConfig;

/**
 * A sequence labeling parser to predict types from a type system generated by DeepType. 
 * The actual sequence labeling implementation can be CRF (Wapiti, with feature engineering 
 * and feature template) or a variety of Depp Learning algoritms via DeLFT (BidLSTM-CRF,
 * BidLSTM-CRF with ELMO, BERT with fine-tuning, etc.).
 *
 * In the DeepType article, a BidLSTM-CRF is used. 
 */
public class TypeSequenceLabeling extends AbstractParser {
    private static final Logger logger = LoggerFactory.getLogger(TypeSequenceLabeling.class);

    private LowerKnowledgeBase wikipedia = null;

    public TypeSequenceLabeling(LowerKnowledgeBase wikipedia) {
        super(GrobidModels.DEEPTYPE, CntManagerFactory.getCntManager(), 
            GrobidCRFEngine.valueOf(wikipedia.getConfig().getSequenceLabelingEngineType()));
        this.wikipedia = wikipedia;
    }

    /*public int createTraining(ArticleTrainingSample articles, 
                                final File trainingOutputPath,
                                final File evalOutputPath,
                                double splitRatio) {
        if ( (articles.getSample() == null) || (articles.getSample().size() == 0) )
            return 0;

        int nbArticle = 0;
        try {
            // the file for writing the training data
            OutputStream os2 = null;
            Writer writer2 = null;
            if (trainingOutputPath != null) {
                os2 = new FileOutputStream(trainingOutputPath);
                writer2 = new OutputStreamWriter(os2, "UTF8");
            }

            // the file for writing the evaluation data
            OutputStream os3 = null;
            Writer writer3 = null;
            if (evalOutputPath != null) {
                os3 = new FileOutputStream(evalOutputPath);
                writer3 = new OutputStreamWriter(os3, "UTF8");
            }
            
            for (Article article : articles.getSample()) {
                System.out.println("Training on " + (nbArticle+1) + " / " + articles.getSample().size());
                StringBuilder trainingBuilder = new StringBuilder();
                if (article instanceof CorpusArticle)
                    trainingBuilder = createTrainingCorpusArticle(article, trainingBuilder);
                else
                    trainingBuilder = createTrainingWikipediaArticle(article, trainingBuilder);  
                
                if ((writer2 == null) && (writer3 != null)) {
                    writer3.write(trainingBuilder.toString());
                    writer3.write("\n");
                } else if ((writer2 != null) && (writer3 == null)) {
                    writer2.write(trainingBuilder.toString());
                    writer2.write("\n");
                } else {
                    if (Math.random() <= splitRatio) {
                        writer2.write(trainingBuilder.toString());
                        writer2.write("\n");
                    } else {
                        writer3.write(trainingBuilder.toString());
                        writer3.write("\n");
                    }
                }
                nbArticle++;
            }

            if (writer2 != null) {
                writer2.close();
                os2.close();
            }

            if (writer3 != null) {
                writer3.close();
                os3.close();
            }
        } catch (Exception e) {
            throw new NerdException("An exception occured while compiling training data from Wikipedia.", e);
        }

        return nbArticle;
    }*/

    static public String addFeatures(String word, String label) {
        FeaturesVectorDeepType featuresVector = new FeaturesVectorDeepType();
        FeatureFactory featureFactory = FeatureFactory.getInstance();
        
        featuresVector.string = word;
        featuresVector.label = label;

        if (word.length() == 1) {
            featuresVector.singleChar = true;
        }

        if (featureFactory.test_all_capital(word))
            featuresVector.capitalisation = "ALLCAPS";
        else if (featureFactory.test_first_capital(word))
            featuresVector.capitalisation = "INITCAP";
        else
            featuresVector.capitalisation = "NOCAPS";

        if (featureFactory.test_number(word))
            featuresVector.digit = "ALLDIGIT";
        else if (featureFactory.test_digit(word))
            featuresVector.digit = "CONTAINDIGIT";
        else
            featuresVector.digit = "NODIGIT";

        Matcher m0 = featureFactory.isPunct.matcher(word);
        if (m0.find()) {
            featuresVector.punctType = "PUNCT";
        }
        if ((word.equals("(")) | (word.equals("["))) {
            featuresVector.punctType = "OPENBRACKET";
        } else if ((word.equals(")")) | (word.equals("]"))) {
            featuresVector.punctType = "ENDBRACKET";
        } else if (word.equals(".")) {
            featuresVector.punctType = "DOT";
        } else if (word.equals(",")) {
            featuresVector.punctType = "COMMA";
        } else if (word.equals("-")) {
            featuresVector.punctType = "HYPHEN";
        } else if (word.equals("\"") | word.equals("\'") | word.equals("`")) {
            featuresVector.punctType = "QUOTE";
        }

        if (featuresVector.capitalisation == null)
            featuresVector.capitalisation = "NOCAPS";

        if (featuresVector.digit == null)
            featuresVector.digit = "NODIGIT";

        if (featuresVector.punctType == null)
            featuresVector.punctType = "NOPUNCT";

        if (featureFactory.test_common(word)) {
            featuresVector.commonName = true;
        }

        //featuresVector.http = isUrl;
        
        return featuresVector.printVector();
    }

}