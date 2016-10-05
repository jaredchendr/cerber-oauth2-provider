# cerber / OAuth2 provider

This is a work-in-progress of Clojurey implementation of [RFC 6749 - The OAuth 2.0 Authorization Framework](https://tools.ietf.org/html/rfc6749).

Currently covers all scenarios described by spec:

* [Authorization Code Grant](https://tools.ietf.org/html/rfc6749#section-4.1)
* [Implict Grant](https://tools.ietf.org/html/rfc6749#section-4.2)
* [Resource Owner Password Credentials Grant](https://tools.ietf.org/html/rfc6749#section-4.3)
* [Client Credentials Grant](https://tools.ietf.org/html/rfc6749#section-4.4)

Tokens expiration and [refreshing](https://tools.ietf.org/html/rfc6749#section-6) are all in the box as well.

To make all of this happen, Cerber sits on a shoulders of Stores.

## Stores

Store is an abstract of storage keeping information vital for Cerber. There are 4 stores implemented:

* users - keeps users details (along with encoded password)
* clients - keeps OAuth clients data (identifiers, secrets, allowed redirect URIs and so on)
* session store - keeps http session data transmitted back and forth via [ring session](https://github.com/ring-clojure/ring/wiki/Sessions)
* tokens store -  generated access- and refresh tokens
* authcodes store - codes to be exchanged for tokens

Store abstract has currenlty following implementations:
* ```in-memory``` - ideal for development mode and tests
* ```redis``` - recommended for production mode
* ```sql``` - any JDBC compliant SQL database (eg. MySQL or PostgreSQL)

To keep maximal flexibility, each store can use different store implementation. It's definitely recommended to use ```in-memory``` stores for development process and persistent ones for production.
Typical configuration might use ```sql``` for users and clients and ```redis``` for sessions / tokens / authcodes.

Now, when it comes to configuration...

## Configuration

Cerber uses glorious [mount](https://github.com/tolitius/mount) to set up everything it needs to operate. Instead of creating stores by hand it's pretty enough to adjust simple edn-based configuration file
specific for each environment (local / test / prod):

``` clojure
{:server {:host "localhost" :port 8090}
 :cerber {:redis-spec {:spec {:host "localhost" :port 6379}}
          :jdbc-pool  {:init-size  1
                       :min-idle   1
                       :max-idle   4
                       :max-active 32
                       :jdbc-url "jdbc:h2:mem:testdb;MODE=MySQL;INIT=RUNSCRIPT FROM 'classpath:/db/schema.sql'"}
          :endpoints  {:authentication "/login"
                       :authorization  "/authorize"}
          :authcodes  {:store :sql :valid-for 180}
          :sessions   {:store :sql :valid-for 180}
          :tokens     {:store :sql :valid-for 180}
          :users      {:store :sql}
          :clients    {:store :sql}
          :realm      "http://defunkt.pl"
          :landing-url "/"}}
```

Words of explanation:

```redis-spec``` is a redis connection specification (look at [carmine](https://github.com/ptaoussanis/carmine) for more info). It's optional if you don't plan to use redis.

```jdbc-pool``` is a sql database pool specification (look at [conman](https://github.com/luminus-framework/conman) for more info). It's optional if you don't plan to use sql-based storages.

```endpoint``` if you ever plan to change default OAuth routes you need to adjust authentication/authorization endpoints here as they are used by OAuth flow.

```realm``` is a realm presented in WWW-Authenticate header in case of 401/403 http error codes

authcodes/sessions/tokens/users and clients are stores configuration. As described previously possible options are ```in-memory```, ```sql``` and ```redis```. Additionally authcodes, sessions and tokens NEED to have life-time (in seconds) configured.

## API

API functions are all grouped in ```cerber.oauth2.core``` namespace and allow to manipulate with clients and tokens at higher level.

### clients

```(create-client [homepage redirects scopes grants authorities approved?])```

used to create new OAuth client, where:
- homepage is a non-validated info string (typically an URL to client's homepage)
- redirects is a validated vector of approved redirect-uris. Note that for security reasons redirect-uri provided with token request should match one of these entries.
- scopes is an optional vector of OAuth scopes that client may request an access to
- authorities is an optional vector of authorities that client may operate with
- approved? is an optional parameter deciding whether client should be auto-approved or not. It's false by default which means that client needs user's approval when requesting access to protected resource.

Example:

```clojure
    (require '[cerber.oauth2.core :as c])

    (c/create-client "http://defunkt.pl"
                     ["http://defunkt.pl/callback"]
                     ["photos:read" "photos:list"]
                     ["moderator"]
                     true)
```

Each generated client has its own random client-id and a secret which both are used in OAuth flow.
Important thing is to keep the secret codes _really_ secret! Both client-id and secret authorize
client instance and it might be harmful to let attacker know what's your client's secret code is.

```(find-client [client-id])```

Looks up for client with given identifier.

```(delete-client [client])```

Removes client from store. Note that together with client all its access- and refresh-tokens are revoked as well.

### tokens

```(find-tokens-by-client [client])```

Returns list of non-expirable refresh-tokens generated for given client.

```(find-tokens-by-user [user])```

Returns list of non-expirable refresh-tokens generated for clients operating on behalf of given user.

```(revoke-tokens [client])```

```(revoke-tokens [client login])```

Revokes all access- and refresh-tokens bound with given client (and optional user's login).
