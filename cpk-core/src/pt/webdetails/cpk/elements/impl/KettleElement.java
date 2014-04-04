/*!
* Copyright 2002 - 2014 Webdetails, a Pentaho company.  All rights reserved.
*
* This software was developed by Webdetails and is provided under the terms
* of the Mozilla Public License, Version 2.0, or any later version. You may not use
* this file except in compliance with the license. If you need a copy of the license,
* please go to  http://mozilla.org/MPL/2.0/. The Initial Developer is Webdetails.
*
* Software distributed under the Mozilla Public License is distributed on an "AS IS"
* basis, WITHOUT WARRANTY OF ANY KIND, either express or  implied. Please refer to
* the license for the specific language governing your rights and limitations.
*/

package pt.webdetails.cpk.elements.impl;


import org.codehaus.jackson.annotate.JsonIgnore;
import org.pentaho.di.core.parameters.NamedParams;
import pt.webdetails.cpk.cache.ICache;
import pt.webdetails.cpk.elements.Element;
import pt.webdetails.cpk.elements.IDataSourceProvider;
import pt.webdetails.cpk.elements.impl.kettleoutputs.IKettleOutput;
import pt.webdetails.cpk.elements.impl.kettleoutputs.InferedKettleOutput;
import pt.webdetails.cpk.elements.impl.kettleoutputs.JsonKettleOutput;
import pt.webdetails.cpk.elements.impl.kettleoutputs.ResultFilesKettleOutput;
import pt.webdetails.cpk.elements.impl.kettleoutputs.ResultOnlyKettleOutput;
import pt.webdetails.cpk.elements.impl.kettleoutputs.SingleCellKettleOutput;

import javax.servlet.http.HttpServletResponse;
import java.util.Collections;
import java.util.Map;

public abstract class KettleElement<TMeta extends NamedParams> extends Element implements IDataSourceProvider {

  protected static final String OUTPUT_NAME_PREFIX = "OUTPUT";

  private static final String CPK_CACHE_RESULTS = "cpk.cacheResults";
  private static final boolean CPK_CACHE_RESULTS_DEFAULT_VALUE = false;

  // TODO: this class should be in the REST layer
  private static class RequestParameterName {
    public static final String STEP_NAME = "stepName";
    public static final String KETTLE_OUTPUT = "kettleOutput";
    public static final String DOWNLOAD = "download";
    public static final String BYPASS_CACHE = "bypassCache";
  }


  private ICache<KettleResultKey, KettleResult> cache;
  protected TMeta meta;

  private boolean cacheResults;

  public boolean isCacheResultsEnabled() {
    return this.cacheResults;
  }

  @Override
  public boolean init( final String pluginId, final String id,
                       final String type, final String filePath, boolean adminOnly ) {
    logger.debug( "Creating Kettle Element from '" + filePath + "'" );

    // call base init
    if ( !super.init( pluginId, id, type, filePath, adminOnly ) ) {
      return false;
    }

    // load  meta info
    this.meta = this.loadMeta( filePath );
    if ( this.meta == null ) {
      logger.error( "Failed to retrieve '" + this.getLocation() + "'" );
      return false;
    }

    // add base parameters to ensure they exist
    KettleElementHelper.addBaseParameters( this.meta );

    // execute at start?
    if ( KettleElementHelper.isExecuteAtStart( this.meta ) ) {
      this.processRequest( Collections.<String, String>emptyMap(), null );
    }

    String cacheResultsStr = KettleElementHelper.getParameterDefault( this.meta, CPK_CACHE_RESULTS );
    this.cacheResults = cacheResultsStr == null ?  CPK_CACHE_RESULTS_DEFAULT_VALUE
      : Boolean.parseBoolean( cacheResultsStr );

    // init was successful
    return true;
  }

  protected abstract TMeta loadMeta( String filePath );


  @Override
  @JsonIgnore // TODO: this JsonIgnore annotation is required due to direct serialization in cpkCoreService.getElementsList() => Refactor getElementsList() to use DTOs
  public ICache<KettleResultKey, KettleResult> getCache() {
    return this.cache;
  }

  @Override
  public KettleElement setCache( ICache<KettleResultKey, KettleResult> cache ) {
    this.cache = cache;
    return this;
  }


  protected final IKettleOutput inferResult( String kettleOutputType, boolean download,
                                             HttpServletResponse httpResponse ) {

     /*
     *  There are a few different types of kettle output processing.
     *  They can be infered or specified from a request parameter: kettleOutput
     *
     *  1. ResultOnly - we'll discard the output and print statistics only
     *  2. ResultFiles - Download the files we have as result filenames
     *  3. Json - Json output of the resultset
     *  4. csv - CSV output of the resultset
     *  5. SingleCell - We'll get the first line, first row
     *  6. Infered - Infering
     *
     *      These do:
     *  3. SingleCell
     *  4. Json
     *  5. CSV
     *  6. Infered
     */

    if ( kettleOutputType == null || kettleOutputType.isEmpty() ) {
      kettleOutputType = "Infered";
    }


    IKettleOutput kettleOutput;
    if ( kettleOutputType.equalsIgnoreCase( "Json" ) ) {
      kettleOutput = new JsonKettleOutput( httpResponse, download );
    } else if ( kettleOutputType.equalsIgnoreCase( "ResultFiles" ) ) {
      kettleOutput = new ResultFilesKettleOutput( httpResponse, download );
    } else if ( kettleOutputType.equalsIgnoreCase( "ResultOnly" ) ) {
      kettleOutput = new ResultOnlyKettleOutput( httpResponse, download );
    } else if ( kettleOutputType.equalsIgnoreCase( "SingleCell" ) ) {
      kettleOutput = new SingleCellKettleOutput( httpResponse, download );
    } else {
      kettleOutput = new InferedKettleOutput( httpResponse, download );
    }

    return kettleOutput;
  }

  // TODO this should be in the REST service layer. This method basically "parses" the bloated map.
  @Override
  public final void processRequest( Map<String, Map<String, Object>> bloatedMap ) {

    // "Parse" bloated map
    Map<String, Object> request = bloatedMap.get( "request" );
    String stepName = (String) request.get( RequestParameterName.STEP_NAME );
    String kettleOutputType = (String) request.get( RequestParameterName.KETTLE_OUTPUT );

    String downloadStr = (String) request.get( RequestParameterName.DOWNLOAD );
    boolean download = Boolean.parseBoolean( downloadStr != null ? downloadStr : "false" );

    String bypassCacheStr = (String) request.get( RequestParameterName.BYPASS_CACHE );
    boolean bypassCache = Boolean.parseBoolean( bypassCacheStr != null ? bypassCacheStr : "false" );


    HttpServletResponse httpResponse = (HttpServletResponse) bloatedMap.get( "path" ).get( "httpresponse" );

    Map<String, String> kettleParameters = KettleElementHelper.getKettleParameters( request );

    this.processRequest( kettleParameters, kettleOutputType , stepName, download, bypassCache, httpResponse );
  }


  // TODO: kettleoutput processing should be in the REST service layer?
  private void processRequest( Map<String, String> kettleParameters, String outputType, String outputStepName,
                               boolean download, boolean bypassCache, HttpServletResponse httpResponse ) {
    KettleResult result = this.processRequest( kettleParameters, outputStepName, bypassCache );
    if ( result != null ) {
      // Choose kettle output type and process result with it
      IKettleOutput kettleOutput = this.inferResult( outputType, download, httpResponse );
      kettleOutput.processResult( result );
      logger.info( "[ " + result + " ]" );
    }
  }


  public KettleResult processRequest( Map<String, String> kettleParameters, String outputStepName,
                                       boolean bypassCache ) {
    KettleResult  result;
    if ( this.isCacheResultsEnabled() ) {
      result = this.processRequestCached( kettleParameters, outputStepName, bypassCache );
    } else {
      result = this.processRequest( kettleParameters, outputStepName );
    }
    return result;
  }


  /**
   * Executes the kettle transformation / job if no cached valued is found or cache bypass is specified.
   * @param kettleParameters Parameters to be passed into the kettle transformation/job.
   * @param outputStepName The step name from where the result will be fetched.
   * @param bypassCache If true, forces the request to be processed even if a value for it already exists in the cache.
   *                    Bypassing the cache also updates the cache with the new obtained result.
   * @return The result of executing the kettle transformation / job.
   */
  private KettleResult processRequestCached( Map<String, String> kettleParameters, String outputStepName,
                                             boolean bypassCache ) {

    KettleResultKey cacheKey = new KettleResultKey( this.getPluginId(), this.getId(),
      outputStepName, kettleParameters );

    KettleResult result;
    if ( !bypassCache ) {
      result = this.getCache().get( cacheKey );
      if ( result != null ) {
        return result; // Cached value found, return it.
      }
    }

    result = this.processRequest( kettleParameters, outputStepName );
    // put new, or update current, result in cache.
    this.getCache().put( cacheKey, result );
    return result;
  }

  /**
   * Executes the kettle transformation / job.
   * @param kettleParameters Parameters to be passed into the kettle transformation/job.
   * @param outputStepName The step name from where the result will be fetched.
   * @return The result of executing the kettle transformation / job.
   */
  protected abstract KettleResult processRequest( Map<String, String> kettleParameters, String outputStepName );

  protected boolean isValidOutputName( String Name ) {
    return Name != null && Name.startsWith( OUTPUT_NAME_PREFIX );
  }

}
