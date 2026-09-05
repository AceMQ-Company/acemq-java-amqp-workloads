# Reporting a vulnerability

Email **security@acemq.com** with what you found and how to reproduce it. Please do
not open a public issue for anything exploitable.

You should get an acknowledgement within two working days, and an assessment of
whether it is a vulnerability, what is affected, and a rough timeline within a week.
If a fix is warranted, we will tell you when it is released and credit you unless you
would rather we did not.

## What is in scope

Everything in this repository. It is a load generator, so it holds broker credentials
and writes reports.

Things worth reporting even if they feel minor:

- A broker password appearing in a generated report, a log line, an exception
  message, or a file left behind after a run.
- A connection made without the TLS verification the configuration asked for.
- A report that embeds untrusted input — a queue name, a broker's error text — in a
  way that executes when the report is opened.
- A run that writes outside the output directory it was given.

## What is not

- **It can overload a broker.** That is what a load generator does. Point it only at
  brokers you are entitled to test; running it against somebody else's is your
  problem, not a vulnerability in this tool.
- **Vulnerabilities in RabbitMQ itself** — report those to Broadcom.
- Findings from a scanner with no demonstrated impact.

## Supported versions

Pre-1.0, only the latest release. There are no maintenance branches yet, so a fix
means a new patch version.

## A note on where you point it

This tool exists to push a broker until it stops behaving. Use it against
infrastructure you own or have written permission to test, and not in production
unless that is a decision somebody made deliberately.
