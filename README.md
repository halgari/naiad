# naiad

A library for building declarative data flow graphs via a fluent api and core.async.

## Rationale
### Premise
Core.async is simple but not easy. That is to say, it is possible to write simple maintainable code using core.async but it
is not a easy task. Often users want to deal with core.async channels in a way that mirrors lazy seqs but are somewhat
hindered by the congitive overhead of using abstractions just as `async/mult` and `async/pub`. Naiad attempts to abstract
the construction of data flow graphs in a declarative way. You specify the operations you want to apply to a set of channels,
and then let Naiad work out how to wire it all up.

### Tenets
The goals of Naiad are as follows:
* Represent dataflow graphs as data, that can be manipulated via post-processing optimizers
* Provide a fluent API that works well with threading macros: `(-> (range 3) (take 2) (map inc))`
* Provide a keyword argument API that works well when needing to connect graphs `(-> (range 3) (take 2 :out o :in))`
* Provide a map based API that works well with programatic data transformers `(->take {:in (range 3) :n 2 :out o})`
* Do *not* introduce a large number of new macros. Should prefer functions over DSLs
* Performance hints (such as parallel pipelining) should not require restructuring of code, it should be as simple as
wrapping a block of code in a macro or function call.



### Example 1:
Let's say you (for some strange reason) wanted to take a sequence of integers, square them,
split them into odds and evens, then decrement the odds and increment the evens, sending the first 100 results into a
output channel. The code may look something like this in core.async

```clojure

(let [in-c (chan (map (fn [x] (* x x))))
      m (mult c)
      odds (chan (comp (filter odd?)
                       (map dec)))
      evens (chan (comp (filter even?)
                        (map inc)))
      final-chan (chan (take 100))]
  (tap m odds)
  (tap m evens)
  (onto-chan in-c (range 1000))
  (pipe (merge odds evens) final-chan)
  (pipe final-chan output-chan))

```

While the core.async code here is not complex, it is rather hard to read and comprehend, the flow of data does not match
the normal human way of reading lisp code. In addition, some constructs like `merge` are ill suited to situations were we
have a pre-existing output channel.

This same code written in Naiad would look something like this:

```clojure

(require '[naiad :as n])

(n/flow
  (let [in-c (->> (n/range 1000)
                  (n/map (fn [x] (* x x))))

        odds (->> in-c
                  (n/filter odd?)
                  (n/map dec))

        evens (->> in-c
                   (n/filter even?)
                   (n/map inc))]
    (->> (n/merge odds evens)
         (n/->take :n 100 :out output-chan :in))))

```

The `flow` macro is not a complex deep-walking macro, but is simply a easy way of setting up some bindings and post-processing
the resulting graph created by the other functions in the body of this snippet. The rest of the code is macro-free (except
for `let` of course), this means that normal clojure constructs like `apply` and `map` will continue to work as expected,
*there is no black magic here*.

## License

Copyright © 2016 Timothy Baldridge

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
