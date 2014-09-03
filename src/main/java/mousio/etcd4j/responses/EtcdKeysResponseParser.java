package mousio.etcd4j.responses;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import io.netty.buffer.ByteBuf;
import org.joda.time.DateTime;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the JSON response for key responses
 */
public class EtcdKeysResponseParser {
  private static final JsonFactory factory = new JsonFactory();

  private static final String ACTION = "action";
  private static final String NODE = "node";
  private static final String PREVNODE = "prevNode";

  private static final String KEY = "key";
  private static final String DIR = "dir";
  private static final String CREATEDINDEX = "createdIndex";
  private static final String MODIFIEDINDEX = "modifiedIndex";
  private static final String VALUE = "value";
  private static final String EXPIRATION = "expiration";
  private static final String TTL = "ttl";
  private static final String NODES = "nodes";

  private static final String CAUSE = "cause";
  private static final String ERRORCODE = "errorCode";
  private static final String MESSAGE = "message";
  private static final String INDEX = "index";

  /**
   * Parses the Json content of the Etcd Response
   *
   * @param content to parse
   * @return EtcdResponse if found in response
   * @throws mousio.etcd4j.responses.EtcdException if exception was found in response
   * @throws java.io.IOException                  if Json parsing or parser creation fails
   */
  public static EtcdKeysResponse parse(ByteBuf content) throws EtcdException, IOException {
    JsonParser parser = factory.createParser(new ByteBufInputStream(content));

    if (parser.nextToken() == JsonToken.START_OBJECT) {
      if (parser.nextToken() == JsonToken.FIELD_NAME
          && parser.getCurrentName().contentEquals(ACTION)) {
        return parseResponse(parser);
      } else {
        throw parseException(parser);
      }
    }

    return null;
  }

  /**
   * Parses an EtcdException
   *
   * @param parser to parse with
   * @return EtcdException
   * @throws java.io.IOException IOException
   */
  private static EtcdException parseException(JsonParser parser) throws IOException {
    EtcdException exception = new EtcdException();

    JsonToken token = parser.getCurrentToken();
    while (token != JsonToken.END_OBJECT && token != null) {
      if( parser.getCurrentName() == CAUSE)
          exception.etcdCause = parser.nextTextValue();
      else if( parser.getCurrentName() == MESSAGE)
          exception.etcdMessage = parser.nextTextValue();
      else if( parser.getCurrentName() == ERRORCODE)
          exception.errorCode = parser.nextIntValue(0);
      else if( parser.getCurrentName() == INDEX)
          exception.index = parser.nextIntValue(0);
      else
          throw new JsonParseException("Unknown field in exception " + parser.getCurrentName(), parser.getCurrentLocation());

      token = parser.nextToken();
    }

    return exception;
  }

  /**
   * Parses response
   *
   * @param parser to parse with
   * @return EtcdResponse
   * @throws java.io.IOException if JSON could not be parsed
   */
  private static EtcdKeysResponse parseResponse(JsonParser parser) throws IOException {
    String action = parser.nextTextValue();

    parser.nextToken(); // Go to the next field
    if (!parser.getCurrentName().contentEquals(NODE)) {
      throw new JsonParseException("Expecting 'node' as second field", parser.getCurrentLocation());
    }
    parser.nextToken(); // Go to the node start

    EtcdKeysResponse response = new EtcdKeysResponse(action, parseNode(parser));
    JsonToken token = parser.nextToken(); // Go past end of object

    if (token == JsonToken.FIELD_NAME) {
      if (!parser.getCurrentName().contentEquals(PREVNODE)) {
        throw new JsonParseException("Expecting 'node' as second field", parser.getCurrentLocation());
      }
      parser.nextToken();
      response.prevNode = parseNode(parser);
      token = parser.nextToken(); // Go past end of object
    }

    if (token == JsonToken.END_OBJECT) {
      return response;
    } else {
      throw new JsonParseException("Unexpected content after response " + token, parser.getCurrentLocation());
    }
  }

  /**
   * Parses a Etcd Node
   *
   * @param parser to use
   * @return Parsed EtcdNode
   * @throws java.io.IOException if JSON content could not be parsed
   */
  private static EtcdKeysResponse.EtcdNode parseNode(JsonParser parser) throws IOException {
    if (parser.getCurrentToken() != JsonToken.START_OBJECT) {
      throw new JsonParseException("Expecting object at start of node description", parser.getCurrentLocation());
    }

    JsonToken token = parser.nextToken();
    EtcdKeysResponse.EtcdNode node = new EtcdKeysResponse.EtcdNode();

    while (token != JsonToken.END_OBJECT && token != null) {

        if (parser.getCurrentName()== KEY)
          node.key = parser.nextTextValue();

        else if (parser.getCurrentName()==CREATEDINDEX)
          node.createdIndex = parser.nextIntValue(0);

        else if (parser.getCurrentName()== MODIFIEDINDEX)
          node.modifiedIndex = parser.nextIntValue(0);

        else if (parser.getCurrentName()== VALUE)
          node.value = parser.nextTextValue();

        else if (parser.getCurrentName()== DIR)
          node.dir = parser.nextBooleanValue();

        else if (parser.getCurrentName()== EXPIRATION)
          node.expiration = DateTime.parse(parser.nextTextValue());

        else if (parser.getCurrentName()== TTL)
          node.ttl = parser.nextIntValue(0);

        else if (parser.getCurrentName()== NODES) {
            parser.nextToken();
            node.nodes = parseNodes(parser);
        }
        else
          throw new JsonParseException("Unknown field " + parser.getCurrentName(), parser.getCurrentLocation());

        token = parser.nextToken();

    }

    return node;
  }

  /**
   * Parses an array with node descriptions
   *
   * @param parser to parse with
   * @return List of EtcdNodes
   * @throws IOException if JSON content could not be parsed
   */
  private static List<EtcdKeysResponse.EtcdNode> parseNodes(JsonParser parser) throws IOException {
    if (parser.getCurrentToken() != JsonToken.START_ARRAY) {
      throw new JsonParseException("Expecting an array of nodes", parser.getCurrentLocation());
    }
    List<EtcdKeysResponse.EtcdNode> nodes = new ArrayList<EtcdKeysResponse.EtcdNode>();

    JsonToken token = parser.nextToken();
    while (token != JsonToken.END_ARRAY && token != null) {
      nodes.add(parseNode(parser));

      token = parser.nextToken();
    }

    return nodes;
  }

  /**
   * Reader for a ByteBuf
   */
  public static class ByteBufInputStream extends InputStream {

    private final ByteBuf byteBuf;

    /**
     * Constructor
     *
     * @param content to parse
     */
    public ByteBufInputStream(ByteBuf content) {
      this.byteBuf = content;
    }

    @Override public int read() throws IOException {
      if (this.byteBuf.isReadable()) {
        return this.byteBuf.readByte();
      } else {
        return -1;
      }
    }
  }
}