package com.scienceminer.nerd.service;

import java.util.*;
import java.io.*;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.HttpHeaders; 

import com.scienceminer.nerd.utilities.NerdRestUtils;
import com.scienceminer.nerd.utilities.NerdServiceProperties;

import org.grobid.core.utilities.LanguageUtilities;
import org.grobid.core.lang.Language;

import com.scienceminer.nerd.disambiguation.*;
import com.scienceminer.nerd.kb.*;
import com.scienceminer.nerd.kb.db.WikipediaDomainMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.json.JSONException;
import org.json.JSONStringer;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.annotation.*;
import com.fasterxml.jackson.core.io.*;

import com.scienceminer.nerd.kb.model.*;
import com.scienceminer.nerd.kb.model.Page.PageType;

/**
 * 
 * REST service to access data in the knowledge base
 * 
 * @author Patrice
 * 
 */
public class NerdRestKB {

	/**
	 * The class Logger.
	 */
	private static final Logger LOGGER = LoggerFactory.getLogger(NerdRestKB.class);

	/**
	 *  Get the information for a concept.
	 * 
	 *  @param id identifier of the concept      
	 *
	 *  @return a response object containing the information related to the identified concept.
	 */
	public static Response getConceptInfo(String id, String lang, NerdRestUtils.Format format) {
		//LOGGER.debug(methodLogIn());       

		Response response = null;
		String retVal = null;
		try {
			//LOGGER.debug(">> set raw text for stateless service'...");
			
			Integer identifier = null;
			try {
				identifier = Integer.parseInt(id);
			} catch(Exception e) {
				LOGGER.error("Could not parse the concept identifier. Bad request.");
				response = Response.status(Status.BAD_REQUEST).build();
			}

			if (identifier != null) {
				NerdEntity entity = new NerdEntity();
				entity.setLang(lang);
				Wikipedia wikipedia = Lexicon.getInstance().getWikipediaConf(lang); 

				if (wikipedia == null) {
					LOGGER.error("Language is not supported. Bad request.");
					response = Response.status(Status.BAD_REQUEST).build();
				} else {
					Page page = wikipedia.getPageById(identifier.intValue());

					// check the type of the page - it must be an article
					PageType type = page.getType();
					if (type != PageType.article) {
						LOGGER.error("Not a valid concept identifier. Bad request.");
						response = Response.status(Status.BAD_REQUEST).build();
					} else {
						Article article = (Article)page;
						entity.setPreferredTerm(article.getTitle());
						entity.setRawName(article.getTitle());
						
						// definition
						Definition definition = new Definition();
						try {
							definition.setDefinition(article.getFirstParagraphMarkup());
						}
						catch(Exception e) {
							LOGGER.debug("Error when getFirstParagraphMarkup for PageID "+ identifier);
						}
						definition.setSource("wikipedia-" + lang);
						definition.setLang(lang);
						entity.addDefinition(definition);

						entity.setWikipediaExternalRef(identifier);

						// categories
						com.scienceminer.nerd.kb.model.Category[] parentCategories = article.getParentCategories();
						if ( (parentCategories != null) && (parentCategories.length > 0) ) {
							for(com.scienceminer.nerd.kb.model.Category theCategory : parentCategories) {
								// not a valid sense if a category of the sense contains "disambiguation" -> this is then a disambiguation page
								if (theCategory == null) {
									LOGGER.warn("Invalid category page for article: " + identifier);
									continue;
								}
								if (theCategory.getTitle() == null) {
									LOGGER.warn("Invalid category content for article: " + identifier);
									continue;
								}
								if (theCategory.getTitle().toLowerCase().indexOf("disambiguation") == -1)
									entity.addCategory(new com.scienceminer.nerd.kb.Category(theCategory));
								else {
									break;
								}
							}
						}

						// translations
						Map<String, Wikipedia> wikipedias = Lexicon.getInstance().getWikipediaConfs();
						Map<String, WikipediaDomainMap> wikipediaDomainMaps = Lexicon.getInstance().getWikipediaDomainMaps();

						entity.setWikipediaMultilingualRef(article.getTranslations(), targetLanguages, wikipedias);

						String json = entity.toJsonFull();
						if (json == null) {
							response = Response.status(Status.INTERNAL_SERVER_ERROR).build();
						}
						else {
							response = Response.status(Status.OK).entity(json)
								.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON+"; charset=UTF-8" )
								.build();
						}
					}
				}
			}
		}
		catch(NoSuchElementException nseExp) {
			LOGGER.error("Could not get a KB instance. Sending service unavailable.");
			response = Response.status(Status.SERVICE_UNAVAILABLE).build();
		} 
		/*catch(JSONException ex) {
			LOGGER.error("Error when building the JSON response string.", ex);  
			System.out.println("Error when building the JSON response string."); 
			response = Response.status(Status.INTERNAL_SERVER_ERROR).build();      
		}*/		
		catch(Exception e) {
			LOGGER.error("An unexpected exception occurs. ", e);
			response = Response.status(Status.INTERNAL_SERVER_ERROR).build();
		}
		//LOGGER.debug(methodLogOut());
		
		return response;
	}


	/**
	 *  Get the list of all ambiguous concepts that can be realized by a given term with 
	 *  associated conditional probability.
	 * 
	 *  @param term the term for which a semantic look-up is realised      
	 *
	 *  @return a response object containing the concept information related to the term.
	 */
	public static Response getTermLookup(String term, String lang, NerdRestUtils.Format format) {
		//LOGGER.debug(methodLogIn());       

		Response response = null;
		String retVal = null;
		String json = null;
		try {
			//LOGGER.debug(">> set raw text for stateless service'...");
			Wikipedia wikipedia = Lexicon.getInstance().getWikipediaConf(lang); 

			if ((term == null) || (term.trim().length() == 0)) {
				LOGGER.error("Empty term. Bad request.");
				response = Response.status(Status.BAD_REQUEST).build();
			} else if (wikipedia == null) {
				LOGGER.error("Language is not supported. Bad request.");
				response = Response.status(Status.BAD_REQUEST).build();
			} else {
				StringBuilder jsonBuilder = new StringBuilder();
				jsonBuilder.append("{ \"term\": \"" + term + "\", \"lang\": \"" + lang + "\", \"senses\" : [");
				
				Label lbl = new Label(wikipedia.getEnvironment(), term);
				if (lbl.exists()) {
					Label.Sense[] senses = lbl.getSenses();
					if ((senses != null) && (senses.length > 0)) {
						boolean first = true;
						for(int i=0; i<senses.length; i++) {
							Label.Sense sense = senses[i];
							//PageType pageType = PageType.values()[sense.getType()];
							PageType pageType = sense.getType();
							if (pageType != PageType.article)
								continue;

							// in case we use a min sense probability filter
							//if (sense.getPriorProbability() < minSenseProbability)
							//	continue; 

							// not a valid sense if title is a list of ...
							String title = sense.getTitle();
							if ((title == null) || title.startsWith("List of") || title.startsWith("Liste des")) 
								continue;
							
							boolean invalid = false;
//System.out.println("check categories for " + sense.getId());							
							com.scienceminer.nerd.kb.model.Category[] parentCategories = sense.getParentCategories();
							if ( (parentCategories != null) && (parentCategories.length > 0) ) {
								for(com.scienceminer.nerd.kb.model.Category theCategory : parentCategories) {
									// not a valid sense if a category of the sense contains "disambiguation" -> this is then a disambiguation page
									if (theCategory == null) {
										LOGGER.warn("Invalid category page for sense: " + title);
										continue;
									}
									if (theCategory.getTitle() == null) {
										LOGGER.warn("Invalid category content for sense: " + title);
										continue;
									}
//System.out.println("categ: " + theCategory.getTitle());
									if (theCategory.getTitle().toLowerCase().indexOf("disambiguation") != -1) {
										invalid = true;
										break;
									}
								}
							}
							if (invalid)
								continue;
							if (first)
								first = false;
							else
								jsonBuilder.append(", ");
							jsonBuilder.append("{ \"pageid\": " + sense.getId() + 
								", \"preferred\" : \"" + sense.getTitle() + "\", \"prob_c\" : " +sense.getPriorProbability() +" }");
						}
					}
				}
				jsonBuilder.append("] }");
				json = jsonBuilder.toString();
			}

			if (json == null) {
				response = Response.status(Status.INTERNAL_SERVER_ERROR).build();
			} else {
				response = Response.status(Status.OK).entity(json)
					.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON+"; charset=UTF-8" )
					.build();
			}

		}
		catch(NoSuchElementException nseExp) {
			LOGGER.error("Could not get a KB instance. Sending service unavailable.");
			response = Response.status(Status.SERVICE_UNAVAILABLE).build();
		} 
		/*catch(JSONException ex) {
			LOGGER.error("Error when building the JSON response string.", ex);  
			System.out.println("Error when building the JSON response string."); 
			response = Response.status(Status.INTERNAL_SERVER_ERROR).build();      
		}*/
		catch(Exception e) {
			LOGGER.error("An unexpected exception occurs. ", e);
			response = Response.status(Status.INTERNAL_SERVER_ERROR).build();
		}
		//LOGGER.debug(methodLogOut());
		
		return response;
	}



	private static List<String> targetLanguages = Arrays.asList("en", "de", "fr");

	/**
	 * @return
	 */
	public static String methodLogIn() {
		return ">> " + NerdRestKB.class.getName() + "." + 
			Thread.currentThread().getStackTrace()[1].getMethodName();
	}

	/**
	 * @return
	 */
	public static String methodLogOut() {
		return "<< " + NerdRestKB.class.getName() + "." + 
			Thread.currentThread().getStackTrace()[1].getMethodName();
	}
}