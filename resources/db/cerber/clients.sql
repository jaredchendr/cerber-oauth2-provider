-- :name find-client :? :*
-- :doc Returns client with given client identifier
select * from clients where id=:id

-- :name insert-client :! :1
-- :doc Inserts new client
insert into clients (id, secret, info, redirects, scopes, grants, approved, created_at) values (:id, :secret, :info, :redirects, :scopes, :grants, :approved?, :created-at)

-- :name delete-client :! :1
-- :doc Deletes client with given identifier
delete from clients where id=:id

-- :name clear-clients :! :1
-- :doc Purges clients table
delete from clients;
